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

package whisk.core.entitlement

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import spray.http.StatusCodes.Forbidden
import Privilege.Privilege
import whisk.common.TransactionId
import whisk.core.entity.Identity
import whisk.core.entity.types.EntityStore
import whisk.core.controller.RejectRequest

class ActionCollection(entityStore: EntityStore) extends Collection(Collection.ACTIONS) {

    protected override val allowedEntityRights = Privilege.ALL

    /**
     * Computes implicit rights on an action (sequence, in package, or primitive).
     */
    protected[core] override def implicitRights(user: Identity, namespaces: Set[String], right: Privilege, resource: Resource)(
        implicit ep: EntitlementProvider, ec: ExecutionContext, transid: TransactionId) = {
        val isOwner = namespaces.contains(resource.namespace.root.asString)
        resource.entity map {
            name =>
                right match {
                    // if action is in a package, check that the user is entitled to package [binding]
                    case (Privilege.PUT | Privilege.READ | Privilege.ACTIVATE) if !resource.namespace.defaultPackage =>
                        val packageNamespace = resource.namespace.root.toPath
                        val packageName = Some(resource.namespace.last.name)
                        val packageResource = Resource(packageNamespace, Collection(Collection.PACKAGES), packageName)

                        if (right == Privilege.PUT) {
                            // only owner can write to package (hence defer to implicit check)
                            // but also assert that package exists otherwise disallow the write
                            super.implicitRights(user, namespaces, right, resource) flatMap {
                                if (_) {
                                    ep.check(user, Privilege.READ, packageResource) map { _ => true }
                                } else {
                                    Future.failed(RejectRequest(Forbidden))
                                }
                            }
                        } else {
                            ep.check(user, Privilege.READ, packageResource) map { _ => true }
                        }

                    case _ => super.implicitRights(user, namespaces, right, resource)
                }
        } getOrElse {
            super.implicitRights(user, namespaces, right, resource)
        }
    }

}
