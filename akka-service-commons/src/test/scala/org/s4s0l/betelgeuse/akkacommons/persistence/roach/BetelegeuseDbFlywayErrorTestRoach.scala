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

import org.s4s0l.betelgeuse.akkacommons.persistence.utils.{BetelegeuseDbFlywayErrorTestBase, BetelgeuseDb}

class BetelegeuseDbFlywayErrorTestRoach extends BetelegeuseDbFlywayErrorTestBase{

  override def afterEach() {
    import scalikejdbc._
    LOGGER.info("calling AFTER EACH!")
    scalike.localTx { implicit session =>
      sql"drop database if EXISTS $TEST_TABLE_SCHEMA".execute().apply()
    }
    scalike.closeAll()
  }
}