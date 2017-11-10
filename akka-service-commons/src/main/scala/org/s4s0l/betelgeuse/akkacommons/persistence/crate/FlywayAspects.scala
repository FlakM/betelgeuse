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



package org.s4s0l.betelgeuse.akkacommons.persistence.crate

import java.sql.{Connection, Types}

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.{Around, Aspect}
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.internal.dbsupport.JdbcTemplate

/**
  * @author Marcin Wielgus
  */
@Aspect
class FlywayAspects {
  @Around("execution(* org.flywaydb.core.internal.dbsupport.DbSupportFactory.createDbSupport(..)) && args(connection, printInfo)")
  def onSingleRequest(pjp: ProceedingJoinPoint, connection: Connection, printInfo: Boolean): Any = {
    try {
      pjp.proceed()
    } catch {
      case a: FlywayException if a.getMessage.startsWith("Unsupported Database: Crate") =>
        new CrateDbSupport(new JdbcTemplate(connection, Types.NULL))
    }
  }

  @Around("execution(* org.flywaydb.core.internal.util.scanner.classpath.FileSystemClassPathLocationScanner.findResourceNames(..)) && args(*, *)")
  def disableFileSystemClasspath(pjp: ProceedingJoinPoint): Any = {
      pjp.proceed()

  }
}
