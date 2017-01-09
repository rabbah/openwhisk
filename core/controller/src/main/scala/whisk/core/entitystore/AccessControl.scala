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

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Success

import spray.http.StatusCodes._
import spray.json.RootJsonFormat
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.controller.RejectRequest
import whisk.core.database.ArtifactStore
import whisk.core.database.DocumentConflictException
import whisk.core.database.DocumentFactory
import whisk.core.database.DocumentTypeMismatchException
import whisk.core.database.NoDocumentException
import whisk.core.entitlement._
import whisk.core.entitlement.Privilege._
import whisk.core.entity._
import whisk.core.entity.types.EntityStore
import whisk.http.Messages._

protected[core] object AccessControl {
    sealed trait CreateOrUpdateMode
    case object CreateOnly extends CreateOrUpdateMode
    case object UpdateOnly extends CreateOrUpdateMode
    case object CreateOrUpdate extends CreateOrUpdateMode
}

protected[core] class AccessControl(
    entityStore: EntityStore,
    entitlementProvider: EntitlementProvider)(
        implicit ec: ExecutionContext) {

    import AccessControl._

    private val logger = entitlementProvider: Logging

    private def collection[A](factory: DocumentFactory[A]): Collection = {
        factory match {
            case WhiskAction     => Collection(Collection.ACTIONS)
            case WhiskActivation => Collection(Collection.ACTIVATIONS)
            case WhiskTrigger    => Collection(Collection.TRIGGERS)
            case WhiskRule       => Collection(Collection.RULES)
            case WhiskPackage    => Collection(Collection.PACKAGES)
        }
    }

    /**
     * Checks access permissions for an entity and if permitted, fetches the entity.
     * If access is not permitted or if the entity fetch fails, rewrite the future into
     * an appropriate RejectRequest.
     *
     * @param user the subject initiating the operation
     * @param factory the factory that can fetch entity of type A from datastore
     * @param datastore the client to the database
     * @param entityName the fully qualified name of the entity
     * @return future that completes with the entity or a reason the request is rejected
     */
    protected[core] def checkAccessAndGetEntity[A, Au >: A](
        user: Identity,
        factory: DocumentFactory[A],
        datastore: ArtifactStore[Au],
        entityName: FullyQualifiedEntityName)(
            implicit transid: TransactionId,
            format: RootJsonFormat[A],
            ma: Manifest[A]): Future[A] = {

        val resource = Resource(entityName.path, collection(factory), Some(entityName.name.asString))

        entitlementProvider.check(user, READ, resource) flatMap {
            _ => factory.get(datastore, entityName.toDocId)
        } recoverWith {
            case (t: NoDocumentException) =>
                Future.failed(RejectRequest(NotFound))
            case (t: DocumentTypeMismatchException) =>
                Future.failed(RejectRequest(Conflict, conformanceMessage))
            case (t: RejectRequest) =>
                Future.failed(t)
            case (t: Throwable) =>
                logger.error(this, s"[GET] failed: $t")
                Future.failed(RejectRequest(InternalServerError))
        } andThen {
            case Success(_) =>
                logger.info(this, s"[GET] entity success")
        }
    }

    protected[core] def checkAccessAndDeleteEntity[A <: WhiskDocument, Au >: A](
        user: Identity,
        factory: DocumentFactory[A],
        datastore: ArtifactStore[Au],
        entityName: FullyQualifiedEntityName)(
            confirm: A => Future[Unit])(
                implicit transid: TransactionId,
                format: RootJsonFormat[A],
                ma: Manifest[A]): Future[A] = {

        val resource = Resource(entityName.path, collection(factory), Some(entityName.name.asString))

        entitlementProvider.check(user, DELETE, resource) flatMap { _ =>
            factory.get(datastore, entityName.toDocId) flatMap { e =>
                confirm(e) flatMap {
                    case _ => factory.del(datastore, e.docinfo) map { _ => e }
                }
            }
        } recoverWith {
            case (t: NoDocumentException) =>
                logger.info(this, s"[DEL] entity does not exist")
                Future.failed(RejectRequest(NotFound))
            case (t: DocumentConflictException) =>
                logger.info(this, s"[DEL] entity conflict: ${t.getMessage}")
                Future.failed(RejectRequest(Conflict, conflictMessage))
            case (t @ RejectRequest(code, message)) =>
                logger.info(this, s"[DEL] entity rejected with code $code: $message")
                Future.failed(t)
            case (t: DocumentTypeMismatchException) =>
                logger.info(this, s"[DEL] entity conformance check failed: ${t.getMessage}")
                Future.failed(RejectRequest(Conflict, conformanceMessage))
            case (t: Throwable) =>
                logger.error(this, s"[DEL] entity failed: ${t.getMessage}")
                Future.failed(RejectRequest(InternalServerError))
        } andThen {
            case Success(_) =>
                logger.info(this, s"[DEL] entity success")
        }
    }

    /**
     * Creates an entity of type A in the datastore iff it doesn't already exist.
     * Assumes that if user is entitled to PUT then user is also entitled to READ.
     *
     * @param user the subject initiating the operation
     * @param factory the factory that can fetch entity of type A from datastore
     * @param datastore the client to the database
     * @param entityName the fully qualified name of the entity
     * @param create a function Option[A] => Future[A] that creates a new entity (the PUT content, receives Some(entity) if it exists else None)
     * @return future that complete with the newly created entity or a reason the request is rejected
     */
    protected[core] def checkAccessAndPutEntity[A, Au >: A](
        user: Identity,
        factory: DocumentFactory[A],
        datastore: ArtifactStore[Au],
        entityName: FullyQualifiedEntityName,
        createOrUpdateMode: CreateOrUpdateMode)(
            createOrUpdate: => Option[A] => Future[A])(
                implicit transid: TransactionId,
                format: RootJsonFormat[A],
                ma: Manifest[A]): Future[A] = {

        val resource = Resource(entityName.path, collection(factory), Some(entityName.name.asString))

        entitlementProvider.check(user, PUT, resource) flatMap { _ =>
            factory.get(datastore, entityName.toDocId) flatMap { e =>
                createOrUpdateMode match {
                    case CreateOnly =>
                        logger.info(this, s"[PUT] entity exists, aborting")
                        Future.failed(RejectRequest(Conflict, entityExists))
                    case CreateOrUpdate | UpdateOnly =>
                        logger.info(this, s"[PUT] entity exists, will try to update '$e'")
                        createOrUpdate(Some(e))
                }
            }
        } recoverWith {
            case _: NoDocumentException =>
                if (createOrUpdateMode != UpdateOnly) {
                    logger.info(this, s"[PUT] entity does not exist, will try to create it")
                    createOrUpdate(None)
                } else {
                    Future.failed(RejectRequest(NotFound, updateNothing))
                }
        } flatMap { e =>
            logger.info(this, s"[PUT] entity created, writing back to datastore")
            factory.put(datastore, e) map { _ => e }
        } recoverWith {
            case (t: DocumentConflictException) =>
                logger.info(this, s"[PUT] entity conflict: ${t.getMessage}")
                Future.failed(RejectRequest(Conflict, conflictMessage))
            case (t @ RejectRequest(code, message)) =>
                logger.info(this, s"[PUT] entity rejected with code $code: $message")
                Future.failed(t)
            case (t: DocumentTypeMismatchException) =>
                logger.info(this, s"[PUT] entity conformance check failed: ${t.getMessage}")
                Future.failed(RejectRequest(Conflict, conformanceMessage))
            case (t: Throwable) =>
                logger.error(this, s"[PUT] entity failed: ${t.getMessage}")
                Future.failed(RejectRequest(InternalServerError))
        } andThen {
            case Success(_) =>
                logger.info(this, s"[PUT] entity success")
        }
    }

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
