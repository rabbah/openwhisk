/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package whisk.core.controller.v2

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.database.NoDocumentException
import whisk.core.entity.UUID
import whisk.core.entity.WhiskAuth
import whisk.core.entity.types.AuthStore
import akka.http.scaladsl.server.directives.Credentials

/** A common trait for secured routes */
trait Authentication extends Logging {
    // FIXME
    implicit val transactionId = TransactionId.unknown

    protected implicit val executionContext: ExecutionContext

    /** Database service to lookup credentials */
    protected val authStore: AuthStore

    protected def validateCredentials(credentials: Credentials): Future[Option[WhiskAuth]] = {
        val f = (credentials match {
            case p @ Credentials.Provided(user) =>
                val uuid = UUID(user)
                info(this, s"authenticate: $uuid")

                WhiskAuth.get(authStore, uuid) map { result =>
                    if (p.verify(result.authkey.key.asString)) {
                        info(this, s"authentication valid")
                        Some(result)
                    } else {
                        info(this, s"authentication not valid")
                        None
                    }
                }

            case _ =>
                Future.successful(None)
        }) recover {
            case _: NoDocumentException | _: IllegalArgumentException =>
                info(this, "authentication not valid")
                None
        }

        f.onFailure({
            case t =>
                info(this, s"authentication error: $t")
        })

        f
    }
}

