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

package org.s4s0l.betelgeuse.akkaauth

import java.security.{Key, PublicKey}

import akka.http.scaladsl.model.MediaTypes
import com.softwaremill.session.{DecodeResult, SessionConfig, SessionEncoder}
import org.s4s0l.betelgeuse.akkaauth.CustomJwtEncoder.JwtAttributes
import org.s4s0l.betelgeuse.akkacommons.serialization.{JacksonJsonSerializable, JacksonJsonSerializer}
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtHeader, JwtJson4s}

import scala.util.Try

/**
  * @author Marcin Wielgus
  */
class CustomJwtEncoder(publicKey: PublicKey, privateKey: Key)
  extends SessionEncoder[AuthenticationInfo] {
  val serializer = new JacksonJsonSerializer()

  override def encode(t: AuthenticationInfo, nowMillis: Long, config: SessionConfig)
  : String = {
    val serialized = serializer.simpleToString(JwtAttributes(t.roles, t.attributes))
    val jwtHeader = JwtHeader(
      algorithm = Some(JwtAlgorithm.RS256),
      typ = Some("jwt"),
      contentType = Some(MediaTypes.`application/json`.toString()),
      keyId = Some("default"))
    val jwtClaim = JwtClaim(
      content = serialized,
      issuer = Some(t.login),
      expiration = Some(t.expiration),
      issuedAt = Some(nowMillis),
      jwtId = Some(t.jwtId)
    )

    JwtJson4s.encode(jwtHeader, jwtClaim, privateKey)
  }

  override def decode(s: String, config: SessionConfig)
  : Try[DecodeResult[AuthenticationInfo]] = {
    val triedTuple = JwtJson4s.decodeAll(s, publicKey, Seq(JwtAlgorithm.RS256))
    triedTuple.map { it =>
      val (h, c, s) = it
      val content = serializer.simpleFromString[JwtAttributes](c.content)
      val info = AuthenticationInfo(
        jwtId = c.jwtId.get,
        login = c.issuer.get,
        roles = content.roles,
        expiration = c.expiration.get,
        attributes = content.attributes
      )
      DecodeResult(info, c.expiration, signatureMatches = true)
    }
  }
}

object CustomJwtEncoder {

  case class JwtAttributes(
                            roles: List[String],
                            attributes: Map[String, String]
                          ) extends JacksonJsonSerializable

}