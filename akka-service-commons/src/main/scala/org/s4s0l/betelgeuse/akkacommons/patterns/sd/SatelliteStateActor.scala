/*
 * Copyright© 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.s4s0l.betelgeuse.akkacommons.patterns.sd

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ShardRegion
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.s4s0l.betelgeuse.akkacommons.BgServiceId
import org.s4s0l.betelgeuse.akkacommons.clustering.client.BgClusteringClientExtension
import org.s4s0l.betelgeuse.akkacommons.clustering.receptionist.BgClusteringReceptionistExtension
import org.s4s0l.betelgeuse.akkacommons.clustering.sharding.BgClusteringShardingExtension
import org.s4s0l.betelgeuse.akkacommons.patterns.message.{Message, Payload}
import org.s4s0l.betelgeuse.akkacommons.patterns.sd.OriginStateDistributor.Protocol.ValidationError
import org.s4s0l.betelgeuse.akkacommons.patterns.sd.SatelliteProtocol._
import org.s4s0l.betelgeuse.akkacommons.patterns.sd.SatelliteStateActor._
import org.s4s0l.betelgeuse.akkacommons.patterns.versionedentity.VersionedEntityActor.Events.{Event, ValueEvent}
import org.s4s0l.betelgeuse.akkacommons.patterns.versionedentity.VersionedEntityActor.Protocol._
import org.s4s0l.betelgeuse.akkacommons.patterns.versionedentity.VersionedEntityActor.ProtocolGetters
import org.s4s0l.betelgeuse.akkacommons.patterns.versionedentity.{VersionedEntityActor, VersionedId}
import org.s4s0l.betelgeuse.akkacommons.serialization.SimpleSerializer
import org.s4s0l.betelgeuse.akkacommons.utils.ActorTarget
import org.s4s0l.betelgeuse.akkacommons.utils.QA.Uuid

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.reflect.ClassTag

/**
  * Adapts [[VersionedEntityActor]] to behave like [[SatelliteProtocol]], so is a remote part
  * of distributed state pattern, also it shares implementation of [[VersionedEntityActor]]
  * it has incompatible protocol as it does not support optimistic lock semantics for
  * new versions of objects,
  *
  * @author Marcin Wielgus
  */
class SatelliteStateActor[I, V](settings: Settings[I, V])(implicit classTag: ClassTag[I])
  extends VersionedEntityActor[V](VersionedEntityActor.Settings(settings.name)) {

  private lazy val serializer = SimpleSerializer(context.system)

  /**
    * true we accept and saved it
    * false we seen it but did not save it locally
    * Error we seen, validation failed
    */
  private val stateChangesProcessed = mutable.Map[VersionedId, Either[Boolean, ValidationError]]()

  implicit val messageForward: Message.ForwardHeaderProvider = Message.defaultForward


  override def processEvent(recover: Boolean): PartialFunction[Event, Unit] =
    super.processEvent(recover) orElse processStateChangeEvents(recover, identity)

  override def receiveCommand: Receive = super.receiveCommand orElse {

    case msg: StateChange[I] =>
      handleStateChangeCommand(msg.asInstanceOf[StateChange[I]])(identity)

    case msg@Message("state-change", messageId, _, _) =>
      val versionedId: VersionedId = VersionedId(msg.get("versionedId").get)
      val value: I = msg.payload.asObject[I](classTag, serializer)
      val expectedConfirmIn: FiniteDuration = msg.ttlAsDurationLeft
      handleStateChangeCommand(StateChange(versionedId, value, expectedConfirmIn, messageId)) {
        case StateChangeOk(_) =>
          msg.response("state-change-ok", "")
        case StateChangeOkWithValidationError(_, errors) =>
          msg.response("state-change-validation-ok", Payload.apply(errors)(serializer))
        case StateChangeNotOk(_, ex) =>
          msg.response("state-change-not-ok", ex.getMessage).withFailed()
      }

    case sd: DistributionComplete =>
      handleStateDistributedCommand(sd)(identity)

    case msg@Message("distribution-complete", messageId, _, _) =>
      val versionedId: VersionedId = VersionedId(msg.get("versionedId").get)
      val expectedConfirmIn: FiniteDuration = msg.ttlAsDurationLeft
      handleStateDistributedCommand(DistributionComplete(versionedId, expectedConfirmIn, messageId)) {
        case DistributionCompleteNotOk(_, ex) =>
          msg.response("distribution-complete-not-ok", ex.getMessage).withFailed()
        case DistributionCompleteOk(_) =>
          msg.response("distribution-complete-ok", "")
      }

  }

  private def handleStateDistributedCommand(msg: DistributionComplete)(responseFactory: (DistributionCompleteResult) => Any) = {
    val DistributionComplete(version, exp, msgId) = msg
    stateChangesProcessed.get(version) match {
      case None | Some(Right(_)) =>
        // we never seen it all seen but validation failed
        sender() ! responseFactory(DistributionCompleteNotOk(msgId, new Exception(s"No value at version $version")))
      case Some(Left(false)) =>
        //we seen it and accepted it before but not saved it, so no need to call listeners
        sender() ! responseFactory(DistributionCompleteOk(msgId))
      case Some(Left(true)) =>
        //we seen it and accepted it before, notifying listeners
        import context.dispatcher
        import org.s4s0l.betelgeuse.utils.AllUtils._
        val senderTmp = sender()

        val request = SatelliteStateListener.StateChanged(version, getValueAtVersion(version).getOrElse(throw new IllegalStateException()), exp)
        settings.listener.configurationChanged(request)
          .map {
            case SatelliteStateListener.StateChangedOk(_) => responseFactory(DistributionCompleteOk(msgId))
            case SatelliteStateListener.StateChangedNotOk(_, ex) => responseFactory(DistributionCompleteNotOk(msgId, ex))
          }
          .recover { case it: Throwable =>
            responseFactory(DistributionCompleteNotOk(msgId, it))
          }
          .pipeToWithTimeout(senderTmp, exp,
            responseFactory(DistributionCompleteNotOk(msgId, new Exception("Timeout!!!"))),
            context.system.scheduler)
    }
  }

  private def handleStateChangeCommand(msg: StateChange[I])(responseFactory: (StateChangeResult) => Any)
  : Unit = {
    stateChangesProcessed.get(msg.versionedId) match {
      case Some(Right(errorS)) =>
        //we seen it with errors before
        sender() ! responseFactory(StateChangeOkWithValidationError(msg.messageId, errorS))
      case Some(Left(true)) =>
        //we seen it and accepted it before
        sender() ! responseFactory(StateChangeOk(msg.messageId))
      case Some(Left(false)) =>
        //we seen it and accepted it before but not saved it
        sender() ! responseFactory(StateChangeOk(msg.messageId))
      case None =>
        //we see it first time
        try {
          val handled = settings.handler(msg.value)
          persist(StateChangedEvent(msg.versionedId, handled, msg.messageId)) {
            processStateChangeEvents(recover = false, responseFactory)
          }
        } catch {
          case ex: Exception =>
            log.error(s"Handler failed for $persistenceId", ex)
            sender() ! responseFactory(StateChangeNotOk(msg.messageId, ex))
        }
    }
  }

  private def processStateChangeEvents(recover: Boolean, responseFactory: (StateChangeResult) => Any)
  : PartialFunction[Event, Unit] = {
    case x@StateChangedEvent(version, _, _, msgId) =>
      x.toHandlerResult match {
        case Left(None) =>
          stateChangesProcessed(version) = Left(false)
          if (!recover) {
            sender() ! responseFactory(StateChangeOk(msgId))
          }
        case Left(Some(value)) =>
          saveValueEvent(ValueEvent[V](msgId, version, value.asInstanceOf[V]))
          stateChangesProcessed(version) = Left(true)
          if (!recover) {
            sender() ! responseFactory(StateChangeOk(msgId))
          }
        case Right(errors) =>
          stateChangesProcessed(version) = Right(errors)
          if (!recover) {
            sender() ! responseFactory(StateChangeOkWithValidationError(msgId, errors))
          }
      }
  }

  /**
    * Unlike [[VersionedEntityActor]] we accept all versions that are unknown to us
    */
  override protected def isVersionAccepted(version: VersionedId): Boolean = getValueAtVersion(version).isEmpty

}

object SatelliteStateActor {

  type HandlerResult[V] = Either[Option[V], ValidationError]

  def startSharded[I, V](settings: Settings[I, V],
                         propsMapper: Props => Props = identity,
                         receptionist: Option[BgClusteringReceptionistExtension] = None)
                        (implicit shardingExt: BgClusteringShardingExtension, classTag: ClassTag[I])
  : Protocol[I, V]
  = {
    val ref = shardingExt.start(s"satellite-state-${settings.name}",
      Props(new SatelliteStateActor[I, V](settings)), entityExtractor)
    receptionist.foreach(_.registerByName(getRemoteName(settings.name), ref))
    new ProtocolImpl[I, V](ref, settings.name)
  }

  private def entityExtractor[I]: ShardRegion.ExtractEntityId = {
    case a: IncomingMessage => (a.entityId, a)
    case a: DistributionComplete => (a.versionedId.id, a)
    case a: StateChange[I] => (a.versionedId.id, a)
    case a@Message("distribution-complete" | "state-change", _, _, _) if a.get("versionedId").isDefined => (VersionedId(a("versionedId")).id, a)
  }

  def getRemote[I](name: String, serviceId: BgServiceId)
                  (implicit clientExt: BgClusteringClientExtension,
                   simpleSerializer: SimpleSerializer)
  : SatelliteProtocol[I]
  = {
    new RemoteSatelliteProtocol(clientExt.client(serviceId).toActorTarget(getRemoteName(name)))
  }

  private def getRemoteName(name: String): String = s"/user/satellite-state-$name"

  trait Protocol[I, V] extends ProtocolGetters[V] with
    SatelliteProtocol[I] {
    def asRemote(implicit simpleSerializer: SimpleSerializer): SatelliteProtocol[I]
  }

  /**
    *
    * @param name     name of actor
    * @param handler  validates and maps incoming value, can return validation error which means we reject
    *                 value, can return None then incoming value will be confirmed but not stored locally
    *                 only marked as received
    *                 or mapped value then it will store the value
    * @param listener to be called on distribution complete
    * @tparam I incoming type
    * @tparam V stored type
    */
  final case class Settings[I, V](name: String,
                                  handler: (I) => HandlerResult[V],
                                  listener: SatelliteStateListener[V])

  /**
    * An protocol for [[SatelliteStateActor]]
    */
  private final class ProtocolImpl[I, V] private[sd](protected[sd] val actorTarget: ActorTarget, shardName: String)
    extends Protocol[I, V] {


    override def asRemote(implicit simpleSerializer: SimpleSerializer): SatelliteProtocol[I] = new RemoteSatelliteProtocol(actorTarget)

    /**
      * distributes state change
      */
    def stateChange(msg: StateChange[I])
                   (implicit executionContext: ExecutionContext, sender: ActorRef)
    : Future[StateChangeResult] = {
      actorTarget.?(msg)(msg.expectedConfirmIn, sender).mapTo[StateChangeResult]
    }

    /**
      * informs that all destinations confirmed
      */
    def distributionComplete(msg: DistributionComplete)
                            (implicit executionContext: ExecutionContext, sender: ActorRef)
    : Future[DistributionCompleteResult] = {
      actorTarget.?(msg)(msg.expectedConfirmIn, sender)
        .mapTo[DistributionCompleteResult]
        .recover { case ex: Throwable => DistributionCompleteNotOk(msg.messageId, new Exception(ex)) }
    }
  }


  /**
    * Adapts satellite protocol for remote message passing via [[org.s4s0l.betelgeuse.akkacommons.patterns.message.Message]] pattern
    *
    * @param actorTarget      actor to ask, should respond with Messages also
    * @param simpleSerializer serializer to be used for marshalling T
    */
  private class RemoteSatelliteProtocol[T](actorTarget: ActorTarget)
                                          (implicit simpleSerializer: SimpleSerializer)
    extends SatelliteProtocol[T] {
    /**
      * distributes state change
      */
    override def stateChange(stateChangeMessage: StateChange[T])
                            (implicit executionContext: ExecutionContext, sender: ActorRef)
    : Future[StateChangeResult] = {
      actorTarget.?(stateChangeMessage.toMessage)(stateChangeMessage.expectedConfirmIn, sender).map {
        case msg@Message("state-change-ok", _, _, _) =>
          StateChangeOk(msg.correlationId)
        case msg@Message("state-change-validation-ok", _, _, _) =>
          StateChangeOkWithValidationError(msg.correlationId, msg.payload.asObject[ValidationError])
        case msg@Message("state-change-not-ok", _, _, _) =>
          StateChangeNotOk(msg.correlationId, new Exception(s"Remote satelliteError: ${msg.failedOpt.getOrElse(-1)}, message was: ${msg.payload.asString}"))
        case _ =>
          StateChangeNotOk(stateChangeMessage.messageId, new Exception(s"Remote satellite unknown response error."))
      }.recover { case ex: Throwable => StateChangeNotOk(stateChangeMessage.messageId, new Exception(ex)) }
    }

    /**
      * informs that all destinations confirmed
      */
    override def distributionComplete(distributionCompleteMessage: DistributionComplete)(implicit executionContext: ExecutionContext, sender: ActorRef)
    : Future[DistributionCompleteResult] = {

      actorTarget.?(distributionCompleteMessage.toMessage)(distributionCompleteMessage.expectedConfirmIn, sender).map {
        case msg@Message("distribution-complete-ok", _, _, _) =>
          DistributionCompleteOk(msg.correlationId)
        case msg@Message("distribution-complete-not-ok", _, _, _) =>
          DistributionCompleteNotOk(msg.correlationId, new Exception(s"Remote satelliteError: ${msg.failedOpt.getOrElse(-1)}, message was: ${msg.payload.asString}"))
        case _ =>
          DistributionCompleteNotOk(distributionCompleteMessage.messageId, new Exception(s"Remote satellite unknown response error."))
      }.recover { case ex: Throwable => DistributionCompleteNotOk(distributionCompleteMessage.messageId, new Exception(ex)) }
    }
  }


  private case class StateChangedEvent[V](versionedId: VersionedId,
                                          @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "type")
                                          handlerValue: Option[V],
                                          validationError: ValidationError,
                                          messageId: Uuid) extends Event {
    def toHandlerResult: HandlerResult[V] = if (validationError.isEmpty) Left(handlerValue) else Right(validationError)
  }

  private object StateChangedEvent {


    def apply[V](versionedId: VersionedId,
                 @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "type")
                 handlerValue: HandlerResult[V],
                 messageId: Uuid): StateChangedEvent[V] = {
      val value = if (handlerValue.isLeft) {
        handlerValue.left.get
      } else {
        None
      }
      val err = if (handlerValue.isRight) {
        handlerValue.right.get
      } else {
        ValidationError(Seq())
      }
      new StateChangedEvent(versionedId, value, err, messageId)
    }

  }

}