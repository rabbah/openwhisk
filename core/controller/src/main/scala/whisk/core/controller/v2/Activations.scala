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

import scala.util.Failure
import scala.util.Success

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import spray.json._
import whisk.core.entitlement.Collection
import whisk.core.entity.EntityName
import whisk.core.entity.WhiskActivation
import whisk.core.entity.types.ActivationStore

trait Activations extends Authentication {

    protected lazy val collection = Collection(Collection.ACTIVATIONS)

    protected val activationStore: ActivationStore

    def activationRoutes = (
        activationListRoute)

    val activationListRoute =
        path("api" / "v2" / "activations") {
            get {
                // missing authorization, filtering by name
                authenticateBasicAsync("whisk rest service", validateCredentials) { whiskAuth =>
                    parameters('skip ? 0, 'limit ? collection.listLimit, 'count ? false, 'docs ? false, 'name.as[EntityName].?) {
                        (skip, limit, count, docs, name) =>

                            val cappedLimit = if (limit <= 0 || limit > 200) 200 else limit
                            val namespace = whiskAuth.toIdentity.namespace.toPath
                            val activations = WhiskActivation.listCollectionInNamespace(activationStore, namespace, skip, cappedLimit, docs, None, None)

                            // FIXME handle case where action name is given

                            val futureJsArray = activations.map { l =>
                                val jsL = if (docs) {
                                    l.right.get.map { _.toExtendedJson }
                                } else {
                                    l.left.get
                                }
                                JsArray(jsL: _*)
                            }

                            complete(futureJsArray)
                    }
                }
            }
        }
}

