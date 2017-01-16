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

import scala.concurrent.Future

import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.whiskVersionBuildno
import whisk.core.WhiskConfig.whiskVersionDate
import whisk.core.controller.RestAPIVersion
import whisk.core.entity.WhiskActivationStore
import whisk.core.entity.WhiskAuthStore

class API(config: WhiskConfig, host: String, port: Int)
    (implicit val actorSystem: ActorSystem) extends AnyRef
    with Activations {

    implicit val executionContext = actorSystem.dispatcher
    implicit val materializer = ActorMaterializer()

    protected implicit val authStore = WhiskAuthStore.datastore(config)
    protected implicit val activationStore = WhiskActivationStore.datastore(config)

    // FIXME: Don't want to extend and have all sorts of Spray definitions.
    // we'll need to decouple the notion of API from Spray, eventually.
    val restAPIVersion = new RestAPIVersion("v2", config(whiskVersionDate), config(whiskVersionBuildno)) {
        override def routes(implicit transid: TransactionId) = ???
    }

    val infoRoute =
        path("api" / "v2") {
            get {
                complete(restAPIVersion.info)
            }
        }

    val allRoutes = {
        extractRequest { request =>
            extractLog { logger =>
                logger.info(request.uri.toString)
                infoRoute ~ activationRoutes
            }
        }

    }

    val bindingFuture = Http().bindAndHandle(allRoutes, host, port)

    def shutdown() : Future[Unit] = {
        bindingFuture.flatMap(_.unbind()).map(_ => ())
    }
}

