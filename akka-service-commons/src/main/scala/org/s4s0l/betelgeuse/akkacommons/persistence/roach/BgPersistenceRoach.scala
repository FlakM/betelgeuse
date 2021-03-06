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

package org.s4s0l.betelgeuse.akkacommons.persistence.roach

import com.typesafe.config.{Config, ConfigFactory}
import org.s4s0l.betelgeuse.akkacommons.persistence.BgPersistence

/**
  * @author Maciej Flak
  */
trait BgPersistenceRoach
  extends BgPersistence {

  private lazy val LOGGER: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[BgPersistenceRoach])

  abstract override def customizeConfiguration: Config = {
    LOGGER.info("Customize config with persistence-roach.conf with fallback to...")
    loadResourceWithPlaceholders("persistence-datasource-roach.conf-template", Map(
      "datasource" -> dataSourceName,
      "schemaname" -> dataSourceSchema,
      "address" -> getSystemProperty("roach.db.host",
        if (serviceInfo.docker) s"${systemName}_db" else "127.0.0.1"),
      "port" -> getSystemProperty("roach.db.port", "26257")
    ))
      .withFallback(ConfigFactory.parseResources("persistence-roach.conf"))
      .withFallback(super.customizeConfiguration)
  }


}
