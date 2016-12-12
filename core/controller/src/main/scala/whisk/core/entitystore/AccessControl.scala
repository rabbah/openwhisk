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

package whisk.core.entitystore

import whisk.core.entitlement._
import whisk.core.entitlement.Privilege._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import whisk.core.entity._
import whisk.core.entity.types.EntityStore
import whisk.common.TransactionId

class AccessControl(
    entityStore: EntityStore,
    entitlementProvider: EntitlementProvider)(
        implicit ec: ExecutionContext) {

    def getAction(user: Identity, name: FullyQualifiedEntityName)(implicit transid: TransactionId): Future[WhiskAction] = {
        val resource = Resource(name.path, Collection(Collection.ACTIONS), Some(name.name.asString))
        entitlementProvider.check(user, READ, resource) flatMap {
            _ => WhiskAction.get(entityStore, name.toDocId)
        }
    }

    /**
     * Traverses a binding recursively to find the root package and
     * merges parameters along the way if mergeParameters flag is set.
     *
     * @param db the entity store containing packages
     * @param pkg the package document id to start resolving
     * @param mergeParameters flag that indicates whether parameters should be merged during package resolution
     * @return the same package if there is no binding, or the actual reference package otherwise
     */
    def resolveBinding(db: EntityStore, pkg: DocId, mergeParameters: Boolean = false)(
        implicit ec: ExecutionContext, transid: TransactionId): Future[WhiskPackage] = {
        WhiskPackage.get(db, pkg) flatMap { wp =>
            // if there is a binding resolve it
            val resolved = wp.binding map { binding =>
                if (mergeParameters) {
                    resolveBinding(db, binding.docid, true) map {
                        resolvedPackage => resolvedPackage.mergeParameters(wp.parameters)
                    }
                } else resolveBinding(db, binding.docid)
            }
            resolved getOrElse Future.successful(wp)
        }
    }

    /**
     * Resolves an action name if it is contained in a package.
     * Look up the package to determine if it is a binding or the actual package.
     * If it's a binding, rewrite the fully qualified name of the action using the actual package path name.
     * If it's the actual package, use its name directly as the package path name.
     */
    def resolveAction(db: EntityStore, fullyQualifiedActionName: FullyQualifiedEntityName)(
        implicit ec: ExecutionContext, transid: TransactionId): Future[FullyQualifiedEntityName] = {
        // first check that there is a package to be resolved
        val entityPath = fullyQualifiedActionName.path
        if (entityPath.defaultPackage) {
            // this is the default package, nothing to resolve
            Future.successful(fullyQualifiedActionName)
        } else {
            // there is a package to be resolved
            val pkgDocId = fullyQualifiedActionName.path.toDocId
            val actionName = fullyQualifiedActionName.name
            resolveBinding(db, pkgDocId) map {
                _.fullyQualifiedName(withVersion = false).add(actionName)
            }
        }
    }

    /**
     * Resolves an action name if it is contained in a package.
     * Look up the package to determine if it is a binding or the actual package.
     * If it's a binding, rewrite the fully qualified name of the action using the actual package path name.
     * If it's the actual package, use its name directly as the package path name.
     * While traversing the package bindings, merge the parameters.
     */
    def resolveActionAndMergeParameters(entityStore: EntityStore, fullyQualifiedName: FullyQualifiedEntityName)(
        implicit ec: ExecutionContext, transid: TransactionId): Future[WhiskAction] = {
        // first check that there is a package to be resolved
        val entityPath = fullyQualifiedName.path
        if (entityPath.defaultPackage) {
            // this is the default package, nothing to resolve
            WhiskAction.get(entityStore, fullyQualifiedName.toDocId)
        } else {
            // there is a package to be resolved
            val pkgDocid = fullyQualifiedName.path.toDocId
            val actionName = fullyQualifiedName.name
            val wp = resolveBinding(entityStore, pkgDocid, mergeParameters = true)
            wp flatMap { resolvedPkg =>
                // fully resolved name for the action
                val fqnAction = resolvedPkg.fullyQualifiedName(withVersion = false).add(actionName)
                // get the whisk action associate with it and inherit the parameters from the package/binding
                WhiskAction.get(entityStore, fqnAction.toDocId) map { _.inherit(resolvedPkg.parameters) }
            }
        }
    }
}
