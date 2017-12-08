/*
 * Copyright© 2017 the original author or authors.
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

package org.s4s0l.betelgeuse.akkacommons.patterns.versionedentity

import akka.pattern._
import akka.util.Timeout
import org.s4s0l.betelgeuse.akkacommons.clustering.sharding.BgClusteringSharding
import org.s4s0l.betelgeuse.akkacommons.patterns.versionedentity.VersionedEntityActor.Protocol._
import org.s4s0l.betelgeuse.akkacommons.patterns.versionedentity.VersionedEntityActor.Settings
import org.s4s0l.betelgeuse.akkacommons.persistence.roach.BgPersistenceJournalRoach
import org.s4s0l.betelgeuse.akkacommons.test.BgTestWithRoachDb

import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * @author Marcin Wielgus
  */
class VersionedEntityActorTest extends
  BgTestWithRoachDb[BgPersistenceJournalRoach with BgClusteringSharding] {

  val to: FiniteDuration = 5 second
  implicit val timeUnit: Timeout = to

  override def createService(): BgPersistenceJournalRoach
    with BgClusteringSharding
  = new BgPersistenceJournalRoach
    with BgClusteringSharding {
  }

  feature("An utility actor is persistent and versioned, supports optimistic locking") {
    scenario("Can be queried for current version") {
      Given("A new shard storing string values named test1")
      val protocol = VersionedEntityActor.startSharded[String](Settings("test1"))(service.clusteringShardingExtension)
      When("Getting version for non existing entity id1")
      protocol.getVersion(GetValueVersion("id1")).pipeTo(self)
      Then("Version returned should have value == 0")
      testKit.expectMsg(to, ValueVersion(VersionedId("id1", 0)))

      When("We store value 'sth' in entity 'id1' via SetValue")
      protocol.setValue(SetValue("id1", "sth")).pipeTo(self)
      Then("We expect it to return confirmation that new version is 1")
      testKit.expectMsg(to, VersionedEntityActor.Protocol.ValueUpdated(VersionedId("id1", 1)))

      When("We ask for a version again")
      protocol.getVersion(GetValueVersion("id1")).pipeTo(self)
      Then("Version returned should now have value == 1")
      testKit.expectMsg(to, ValueVersion(VersionedId("id1", 1)))

    }


    scenario("Getting values") {

      Given("A new shard storing string values named test3")
      val protocol = VersionedEntityActor.startSharded[String](Settings("test3"))(service.clusteringShardingExtension)
      Given("entity 'id1' has value 'sth' in version 2")
      protocol.setValue(SetValue("id1", "sth1")).pipeTo(self)
      testKit.expectMsg(to, VersionedEntityActor.Protocol.ValueUpdated(VersionedId("id1", 1)))
      protocol.setValue(SetValue("id1", "sth2")).pipeTo(self)
      testKit.expectMsg(to, VersionedEntityActor.Protocol.ValueUpdated(VersionedId("id1", 2)))


      When("We ask for version 1")
      protocol.getValue(GetValue(VersionedId("id1", 1))).pipeTo(self)
      Then("We expect it to return a value 'sth1'")
      testKit.expectMsg(to, VersionedEntityActor.Protocol.Value(VersionedId("id1", 1), Some("sth1")))

      When("We ask for version 2")
      protocol.getValue(GetValue(VersionedId("id1", 2))).pipeTo(self)
      Then("We expect it to return a value 'sth2'")
      testKit.expectMsg(to, VersionedEntityActor.Protocol.Value(VersionedId("id1", 2), Some("sth2")))

      When("We ask for version 3")
      protocol.getValue(GetValue(VersionedId("id1", 3))).pipeTo(self)
      Then("We expect it to return a None in version 3")
      testKit.expectMsg(to, VersionedEntityActor.Protocol.Value(VersionedId("id1", 3), None))
    }


    scenario("Optimistic locking") {
      Given("A new shard storing string values named test2")
      val protocol = VersionedEntityActor.startSharded[String](Settings("test2"))(service.clusteringShardingExtension)
      Given("entity 'id1' has value 'sth' in version 2")
      protocol.setValue(SetValue("id1", "sth")).pipeTo(self)
      testKit.expectMsg(to, VersionedEntityActor.Protocol.ValueUpdated(VersionedId("id1", 1)))
      protocol.setValue(SetValue("id1", "sth")).pipeTo(self)
      testKit.expectMsg(to, VersionedEntityActor.Protocol.ValueUpdated(VersionedId("id1", 2)))


      When("We try to update its value with version 2")
      protocol.setVersionedValue(SetVersionedValue(VersionedId("id1", 2), "sthnew")).pipeTo(self)

      Then("The result is failure with version 2")
      testKit.expectMsg(to, VersionedEntityActor.Protocol.ValueUpdateOptimisticError(VersionedId("id1", 2)))


      When("We update with version 4")
      protocol.setVersionedValue(SetVersionedValue(VersionedId("id1", 4), "sthnew")).pipeTo(self)
      Then("The result is still failure with version 2")
      testKit.expectMsg(to, VersionedEntityActor.Protocol.ValueUpdateOptimisticError(VersionedId("id1", 2)))

      When("We update with version 3")
      protocol.setVersionedValue(SetVersionedValue(VersionedId("id1", 3), "sthnew")).pipeTo(self)
      Then("The result is success with version 3")
      testKit.expectMsg(to, VersionedEntityActor.Protocol.ValueUpdated(VersionedId("id1", 3)))


    }

    scenario("Setting should contain tags in responses") {
      Given("A new shard storing string values named test5")
      val protocol = VersionedEntityActor.startSharded[String](Settings("test5"))(service.clusteringShardingExtension)

      When("setting entity 'id1' has value 'sth1' with tag 'X'")
      protocol.setValue(SetValue("id1", "sth1", Some("X"))).pipeTo(self)
      Then("value updated response has tag 'X'")
      testKit.expectMsg(to, VersionedEntityActor.Protocol.ValueUpdated(VersionedId("id1", 1), Some("X")))

      When("setting versioned entity 'id1' has value 'sth2' in version 2 with tag 'Y'")
      protocol.setVersionedValue(SetVersionedValue(VersionedId("id1", 2), "sth2", Some("Y"))).pipeTo(self)
      Then("value updated response has tag 'Y'")
      testKit.expectMsg(to, VersionedEntityActor.Protocol.ValueUpdated(VersionedId("id1", 2), Some("Y")))


      When("setting versioned entity 'id1' has value 'sth-other' in version 2 with tag 'Z'")
      protocol.setVersionedValueMsg(SetVersionedValue(VersionedId("id1", 2), "sth-other", Some("Z")))(self)
      Then("ValueUpdateOptimisticError response has tag 'Z'")
      testKit.expectMsg(to, VersionedEntityActor.Protocol.ValueUpdateOptimisticError(VersionedId("id1", 2), Some("Z")))

    }

  }
}