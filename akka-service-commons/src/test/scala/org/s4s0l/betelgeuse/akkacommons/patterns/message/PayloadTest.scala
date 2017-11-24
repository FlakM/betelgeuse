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

package org.s4s0l.betelgeuse.akkacommons.patterns.message

import akka.util.ByteString
import org.scalatest.FeatureSpec

/**
  * @author Marcin Wielgus
  */
class PayloadTest extends FeatureSpec {

  feature("Payload can be bytes or string, but should be usable as both") {
    scenario ("Create as both string or byte") {
      val payload:Payload = "somepayload"
      assert(payload.asString == "somepayload")
      assert(payload.asBytes == ByteString("somepayload"))

      val payloadFromBytes:Payload = ByteString("somepayload")
      assert(payloadFromBytes.asString == "somepayload")
      assert(payloadFromBytes.asBytes == ByteString("somepayload"))

    }

  }

}
