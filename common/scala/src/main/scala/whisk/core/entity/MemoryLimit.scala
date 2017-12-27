/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.entity

import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import spray.json.JsNumber
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError
import whisk.core.entity.size.SizeInt
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.{actionMemoryMin, actionMemoryMax, actionMemoryDefault}

/**
 * MemoyLimit encapsulates allowed memory for an action. The limit must be within a
 * permissible range (by default [128MB, 512MB]).
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param megabytes the memory limit in megabytes for the action
 */
protected[entity] class MemoryLimit private (val megabytes: Int) extends AnyVal {}

protected[core] object MemoryLimit extends ArgNormalizer[MemoryLimit] {
  // FIXME: reading the memory limits from sys.env instead of a canonical property reader
  // because WhiskConfig requires a logger, which requires an actor system, neither of
  // which are readily available here; rather than introduce significant refactoring,
  // defer this fix until WhiskConfig is refactored itself, which is planned to introduce
  // type safe properties
  protected[core] val minMemory = ByteSize.fromString(WhiskConfig.readFromEnv(actionMemoryMin).getOrElse("128M"))
  protected[core] val maxMemory = ByteSize.fromString(WhiskConfig.readFromEnv(actionMemoryMax).getOrElse("512M"))
  protected[core] val stdMemory = ByteSize.fromString(WhiskConfig.readFromEnv(actionMemoryDefault).getOrElse("256M"))

  /** Gets MemoryLimit with default value */
  protected[core] def apply(): MemoryLimit = MemoryLimit(stdMemory)

  /**
   * Creates MemoryLimit for limit, iff limit is within permissible range.
   *
   * @param megabytes the limit in megabytes, must be within permissible range
   * @return MemoryLimit with limit set
   * @throws IllegalArgumentException if limit does not conform to requirements
   */
  @throws[IllegalArgumentException]
  protected[core] def apply(megabytes: ByteSize): MemoryLimit = {
    require(megabytes >= minMemory, s"memory $megabytes below allowed threshold of $minMemory")
    require(megabytes <= maxMemory, s"memory $megabytes exceeds allowed threshold of $maxMemory")
    new MemoryLimit(megabytes.toMB.toInt);
  }

  override protected[core] implicit val serdes = new RootJsonFormat[MemoryLimit] {
    def write(m: MemoryLimit) = JsNumber(m.megabytes)

    def read(value: JsValue) =
      Try {
        val JsNumber(mb) = value
        require(mb.isWhole(), "memory limit must be whole number")
        MemoryLimit(mb.intValue MB)
      } match {
        case Success(limit)                       => limit
        case Failure(e: IllegalArgumentException) => deserializationError(e.getMessage, e)
        case Failure(e: Throwable)                => deserializationError("memory limit malformed", e)
      }
  }
}
