/*
 * Copyright© 2017 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */


//above is a lie
package org.s4s0l.betelgeuse.akkacommons.serialization

import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import com.typesafe.config.ConfigFactory
import org.scalatest.{FeatureSpec, Matchers}

class JacksonJsonSerializerTest extends FeatureSpec with Matchers {

//

  feature("Akka serialzation to json with jackson") {
    scenario("serializer") {
      val serializer = new JacksonJsonSerializer()
      val a = Animal("our cat", 12, Cat("black", tail = true))
      val bytes = serializer.toBinary(a)
      val ar = serializer.fromBinary(bytes, classOf[Animal]).asInstanceOf[Animal]
      assert(a == ar)
    }


    scenario("serializer - scala map") {
      val serializer = new JacksonJsonSerializer()
      val a: Map[String, Any] = Map("a" -> 1, "b" -> Map("1" -> "0"))
      val bytes = serializer.toBinary(a)
      val ar = serializer.fromBinary(bytes, classOf[Map[String, Any]]).asInstanceOf[Map[String, Any]]
      assert(a == ar)
    }


    scenario("Registering the serializer works") {
      val system = ActorSystem("JacksonJsonSerializerTest", ConfigFactory.load("JacksonJsonSerializerTest.conf"))

      val serialization = SerializationExtension.get(system)
      assert(classOf[JacksonJsonSerializer] == serialization.serializerFor(classOf[Animal]).getClass)

      system.terminate()
    }

    scenario("DepricatedTypeWithMigrationInfo") {
      val serializer = new JacksonJsonSerializer()
      val bytes = serializer.toBinary(OldType("12"))
      assert(NewType(12) == serializer.fromBinary(bytes, classOf[OldType]))
    }

    scenario("verifySerialization - no error") {
      JacksonJsonSerializer.setVerifySerialization(true)
      val serializer = new JacksonJsonSerializer()
      val a = Animal("our cat", 12, Cat("black", tail = true))
      val ow = ObjectWrapperWithTypeInfo(a)
      serializer.toBinary(ow)
    }

    scenario("verifySerialization - with error") {
      JacksonJsonSerializer.setVerifySerialization(true)
      val serializer = new JacksonJsonSerializer()
      val a = Animal("our cat", 12, Cat("black", tail = true))
      val ow = ObjectWrapperWithoutTypeInfo(a)
      intercept[JacksonJsonSerializerVerificationFailed] {
        serializer.toBinary(ow)
      }
    }

    scenario("verifySerialization - disabled") {
      JacksonJsonSerializer.setVerifySerialization(true)
      val serializer = new JacksonJsonSerializer()
      val a = Animal("our cat", 12, Cat("black", tail = true))
      val ow = ObjectWrapperWithoutTypeInfoOverrided(a)
      serializer.toBinary(ow)
    }


  }
}
