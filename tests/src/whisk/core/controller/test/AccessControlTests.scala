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

package whisk.core.controller.test

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner

import akka.event.Logging.InfoLevel
import common.WskActorSystem
import spray.http.StatusCodes._
import whisk.common.TransactionCounter
import whisk.core.WhiskConfig
import whisk.core.controller._
import whisk.core.database.test.MockDocumentFactory
import whisk.core.entitlement._
import whisk.core.entity._
import whisk.core.entitystore.AccessControl
import whisk.core.entitystore.AccessControl._
import whisk.core.iam.NamespaceProvider
import scala.concurrent.Future
import whisk.http.Messages
import whisk.core.database.DocumentFactory
import spray.json.RootJsonFormat
import whisk.common.TransactionId

@RunWith(classOf[JUnitRunner])
class AccessControlTests
    extends FlatSpec
    with Matchers
    with WskActorSystem
    with TransactionCounter
    with ScalaFutures {

    val whiskConfig = new WhiskConfig(WhiskServices.requiredProperties)
    val loadBalancer = new DegenerateLoadBalancerService(whiskConfig, InfoLevel)
    val iam = new NamespaceProvider(whiskConfig, forceLocal = true)
    val entitlementProvider = new LocalEntitlementProvider(whiskConfig, loadBalancer, iam)
    val entityStore = new MockDocumentFactory[WhiskEntity](WhiskEntityJsonFormat)
    val accessControl: AccessControl = new AccessControl(entityStore, entitlementProvider)
    entitlementProvider.setVerbosity(InfoLevel)
    Collection.initialize(entityStore, InfoLevel)

    val someUser = Subject("someone").toIdentity(AuthKey())
    val guestUser = Subject("anonymous").toIdentity(AuthKey())

    val timeout = 1 second

    def check[W](f: Future[W], r: Either[Throwable, W]) = {
        Await.ready(f, timeout).eitherValue.get shouldBe r
    }

    ignore should "not allow get access to entity in another namespace whether it exsists or not" in {
        testGetFromDefaultPackage()

        val privatePkg = WhiskPackage(someUser.namespace.toPath, EntityName("privatePkg"), publish = false)
        testGetFromPackage(privatePkg)

        val publicPkg = WhiskPackage(someUser.namespace.toPath, EntityName("publicPkg"), publish = true)
        testGetFromPackage(publicPkg)
    }

    ignore should "not allow delete access to entity in another namespace whether it exists or not" in {
        testDelFromDefaultPackage()

        val privatePkg = WhiskPackage(someUser.namespace.toPath, EntityName("privatePkg"), publish = false)
        testDelFromPackage(privatePkg)

        val publicPkg = WhiskPackage(someUser.namespace.toPath, EntityName("publicPkg"), publish = true)
        testDelFromPackage(publicPkg)
    }

    it should "not allow put access to entity in another namespace" in {
        testCreateInDefaultPackage()
        testUpdateInDefaultPackage()
        testPutInDefaultPackage()

        // now in a package
        val privatePkg = WhiskPackage(someUser.namespace.toPath, EntityName("privatePkg"), publish = false)
        testPutToPackage(privatePkg)

        val publicPkg = WhiskPackage(someUser.namespace.toPath, EntityName("publicPkg"), publish = true)
        testPutToPackage(publicPkg)
    }

    def testDelFromDefaultPackage() = {
        implicit val tid = transid()

        val action = WhiskAction(someUser.namespace.toPath, EntityName("a"), Exec.js("???"))
        assert(!entityStore.contains(action.docid))

        check(del(guestUser, action.namespace, action.name.asString, WhiskAction), Left(RejectRequest(Forbidden)))
        check(del(someUser, action.namespace, action.name.asString, WhiskAction), Left(RejectRequest(NotFound)))

        Await.result(WhiskAction.put(entityStore, action), timeout)
        assert(entityStore.contains(action.docid))

        check(del(guestUser, action.namespace, action.name.asString, WhiskAction), Left(RejectRequest(Forbidden)))
        check(del(guestUser, action.namespace, action.name.asString, WhiskTrigger), Left(RejectRequest(Forbidden)))

        check(del(someUser, action.namespace, action.name.asString, WhiskTrigger), Left(RejectRequest(Conflict, Messages.conformanceMessage)))
        check(del(someUser, action.namespace, action.name.asString, WhiskAction), Right(action))
        assert(!entityStore.contains(action.docid))
    }

    def testGetFromDefaultPackage() = {
        implicit val tid = transid()

        val action = WhiskAction(someUser.namespace.toPath, EntityName("a"), Exec.js("???"))
        assert(!entityStore.contains(action.docid))

        check(get(guestUser, action.namespace, action.name.asString, WhiskAction), Left(RejectRequest(Forbidden)))
        check(get(someUser, action.namespace, action.name.asString, WhiskAction), Left(RejectRequest(NotFound)))

        Await.result(WhiskAction.put(entityStore, action), timeout)

        check(get(guestUser, action.namespace, action.name.asString, WhiskAction), Left(RejectRequest(Forbidden)))
        check(get(guestUser, action.namespace, action.name.asString, WhiskTrigger), Left(RejectRequest(Forbidden)))

        check(get(someUser, action.namespace, action.name.asString, WhiskAction), Right(action))
        check(get(someUser, action.namespace, action.name.asString, WhiskTrigger), Left(RejectRequest(Conflict, Messages.conformanceMessage)))

        Await.result(WhiskAction.del(entityStore, action.docinfo), timeout)
        assert(!entityStore.contains(action.docid))
    }

    def testCreateInDefaultPackage() = {
        implicit val tid = transid()

        val action = WhiskAction(someUser.namespace.toPath, EntityName("a"), Exec.js("???"))
        assert(!entityStore.contains(action.docid))

        //// create only
        check(create(guestUser, action.namespace, WhiskAction, action), Left(RejectRequest(Forbidden)))
        assert(!entityStore.contains(action.docid))

        check(create(someUser, action.namespace, WhiskAction, action), Right(action))
        assert(entityStore.contains(action.docid))

        // repeat with pre-existing action
        check(create(guestUser, action.namespace, WhiskAction, action), Left(RejectRequest(Forbidden)))
        check(create(someUser, action.namespace, WhiskAction, action), Left(RejectRequest(Conflict, Messages.entityExists)))

        Await.result(WhiskAction.del(entityStore, action.docinfo), timeout)
        assert(!entityStore.contains(action.docid))
    }

    def testUpdateInDefaultPackage() = {
        implicit val tid = transid()

        val action = WhiskAction(someUser.namespace.toPath, EntityName("a"), Exec.js("???"))
        assert(!entityStore.contains(action.docid))

        //// update only if doesn't exist
        check(update(guestUser, action.namespace, WhiskAction, action), Left(RejectRequest(Forbidden)))
        assert(!entityStore.contains(action.docid))

        check(update(someUser, action.namespace, WhiskAction, action), Left(RejectRequest(NotFound, Messages.updateNothing)))
        assert(!entityStore.contains(action.docid))

        Await.result(WhiskAction.put(entityStore, action), timeout)
        val updatedAction = WhiskAction(action.namespace, action.name, action.exec, version = action.version.upPatch)

        // now repeat with pre-existing action
        check(update(guestUser, action.namespace, WhiskAction, action), Left(RejectRequest(Forbidden)))
        check(update(someUser, action.namespace, WhiskAction, action), Right(updatedAction))

        // now delete it and start over
        Await.result(WhiskAction.del(entityStore, action.docinfo), timeout)
        assert(!entityStore.contains(action.docid))
    }

    def testPutInDefaultPackage() = {
        implicit val tid = transid()

        val action = WhiskAction(someUser.namespace.toPath, EntityName("a"), Exec.js("???"))
        val updatedAction = WhiskAction(action.namespace, action.name, action.exec, version = action.version.upPatch)
        assert(!entityStore.contains(action.docid))

        //// create or update
        check(put(guestUser, action.namespace, action.name.asString, WhiskAction, action), Left(RejectRequest(Forbidden)))
        assert(!entityStore.contains(action.docid))

        check(put(someUser, action.namespace, action.name.asString, WhiskAction, action), Right(action))
        assert(entityStore.contains(action.docid))

        check(put(someUser, action.namespace, action.name.asString, WhiskAction, action), Right(updatedAction))
        assert(entityStore.contains(action.docid))

        Await.result(WhiskAction.del(entityStore, action.docinfo), timeout)
        assert(!entityStore.contains(action.docid))
    }

    def testDelFromPackage(pkg: WhiskPackage) = {
        implicit val tid = transid()

        val pkgAction = WhiskAction(pkg.fullPath, EntityName("a"), Exec.js("???"))
        assert(!entityStore.contains(pkgAction.docid))
        assert(!entityStore.contains(pkg.docid))

        check(del(guestUser, pkgAction.namespace, pkgAction.name.asString, WhiskAction), Left(RejectRequest(Forbidden)))
        check(del(someUser, pkgAction.namespace, pkgAction.name.asString, WhiskAction), Left(RejectRequest(NotFound)))

        Await.result(WhiskPackage.put(entityStore, pkg), timeout)
        assert(entityStore.contains(pkg.docid))

        check(del(guestUser, pkg.namespace, pkg.name.asString, WhiskPackage), Left(RejectRequest(Forbidden)))
        check(del(guestUser, pkgAction.namespace, pkgAction.name.asString, WhiskAction), Left(RejectRequest(Forbidden)))
        check(del(someUser, pkgAction.namespace, pkgAction.name.asString, WhiskAction), Left(RejectRequest(NotFound)))

        Await.result(WhiskAction.put(entityStore, pkgAction), timeout)
        assert(entityStore.contains(pkgAction.docid))

        // may not delete action in public package owned by someone else
        check(del(guestUser, pkgAction.namespace, pkgAction.name.asString, WhiskAction), Left(RejectRequest(Forbidden)))
        check(del(guestUser, pkgAction.namespace, pkgAction.name.asString, WhiskTrigger), Left(RejectRequest(Forbidden)))

        check(del(someUser, pkgAction.namespace, pkgAction.name.asString, WhiskTrigger), Left(RejectRequest(Conflict, Messages.conformanceMessage)))
        // do this last since it deletes the action
        check(del(someUser, pkgAction.namespace, pkgAction.name.asString, WhiskAction), Right(pkgAction))

        Await.result(WhiskAction.del(entityStore, pkg.docinfo), timeout)
        Await.result(WhiskAction.del(entityStore, pkgAction.docinfo), timeout)
        assert(entityStore.isEmpty)
    }

    def testGetFromPackage(pkg: WhiskPackage) = {
        implicit val tid = transid()

        val pkgAction = WhiskAction(pkg.fullPath, EntityName("a"), Exec.js("???"))
        assert(!entityStore.contains(pkg.docid))
        assert(!entityStore.contains(pkgAction.docid))

        check(get(guestUser, pkgAction.namespace, pkgAction.name.asString, WhiskAction), Left(RejectRequest(Forbidden)))
        check(get(someUser, pkgAction.namespace, pkgAction.name.asString, WhiskAction), Left(RejectRequest(NotFound)))

        Await.result(WhiskPackage.put(entityStore, pkg), timeout)
        assert(entityStore.contains(pkg.docid))

        check(get(guestUser, pkg.namespace, pkg.name.asString, WhiskPackage), if (pkg.publish) Right(pkg) else Left(RejectRequest(Forbidden)))
        check(get(someUser, pkg.namespace, pkg.name.asString, WhiskPackage), Right(pkg))

        check(get(guestUser, pkgAction.namespace, pkgAction.name.asString, WhiskAction), Left(RejectRequest(if (pkg.publish) NotFound else Forbidden)))
        check(get(someUser, pkgAction.namespace, pkgAction.name.asString, WhiskAction), Left(RejectRequest(NotFound)))

        Await.result(WhiskAction.put(entityStore, pkgAction), timeout)
        assert(entityStore.contains(pkgAction.docid))

        check(get(guestUser, pkgAction.namespace, pkgAction.name.asString, WhiskAction), if (pkg.publish) Right(pkgAction) else Left(RejectRequest(Forbidden)))
        // because trigger/rule dare not allowed in packages and hence only the root path is checked
        check(get(guestUser, pkgAction.namespace, pkgAction.name.asString, WhiskTrigger), Left(RejectRequest(Forbidden)))

        check(get(someUser, pkgAction.namespace, pkgAction.name.asString, WhiskAction), Right(pkgAction))
        check(get(someUser, pkgAction.namespace, pkgAction.name.asString, WhiskTrigger), Left(RejectRequest(Conflict, Messages.conformanceMessage)))

        Await.result(WhiskAction.del(entityStore, pkg.docinfo), timeout)
        Await.result(WhiskAction.del(entityStore, pkgAction.docinfo), timeout)
        assert(entityStore.isEmpty)
    }

    def testPutToPackage(pkg: WhiskPackage) = {
        implicit val tid = transid()

        val pkgAction = WhiskAction(pkg.fullPath, EntityName("a"), Exec.js("???"))
        assert(!entityStore.contains(pkg.docid))
        assert(!entityStore.contains(pkgAction.docid))

        check(put(guestUser, pkgAction.namespace, pkgAction.name.asString, WhiskAction, pkgAction), Left(RejectRequest(Forbidden)))
        check(put(someUser, pkgAction.namespace, pkgAction.name.asString, WhiskAction, pkgAction), Left(RejectRequest(NotFound)))

        check(put(guestUser, pkg.namespace, pkg.name.asString, WhiskPackage, pkg), Left(RejectRequest(Forbidden)))
        assert(!entityStore.contains(pkg.docid))

        check(put(someUser, pkg.namespace, pkg.name.asString, WhiskPackage, pkg), Right(pkg))
        assert(entityStore.contains(pkg.docid))

        check(put(guestUser, pkgAction.namespace, pkgAction.name.asString, WhiskAction, pkgAction), Left(RejectRequest(Forbidden)))
        check(put(someUser, pkgAction.namespace, pkgAction.name.asString, WhiskAction, pkgAction), Right(pkgAction))
        assert(entityStore.contains(pkgAction.docid))

        Await.result(WhiskAction.del(entityStore, pkg.docinfo), timeout)
        Await.result(WhiskAction.del(entityStore, pkgAction.docinfo), timeout)
        assert(entityStore.isEmpty)
    }

    def get[W <: WhiskEntity](who: Identity, path: EntityPath, name: String, kind: DocumentFactory[W])(
        implicit format: RootJsonFormat[W], ma: Manifest[W], transid: TransactionId) = {
        accessControl.checkAccessAndGetEntity(
            who,
            kind,
            entityStore,
            FullyQualifiedEntityName(path, EntityName(name)))
    }

    def del[W <: WhiskEntity](who: Identity, path: EntityPath, name: String, kind: DocumentFactory[W])(
        implicit format: RootJsonFormat[W], ma: Manifest[W], transid: TransactionId) = {
        accessControl.checkAccessAndDeleteEntity(who,
            kind,
            entityStore,
            FullyQualifiedEntityName(path, EntityName(name))) {
                _ => Future.successful({})
            }
    }

    // create only
    def create[W <: WhiskEntity](who: Identity, path: EntityPath, kind: DocumentFactory[W], e: W)(
        implicit format: RootJsonFormat[W], ma: Manifest[W], transid: TransactionId) = {
        accessControl.checkAccessAndPutEntity(
            who,
            kind,
            entityStore,
            FullyQualifiedEntityName(path, EntityName("a")),
            CreateOnly) { pre =>
                pre shouldBe empty
                Future.successful(e)
            }
    }

    // update only
    def update[W <: WhiskEntity](who: Identity, path: EntityPath, kind: DocumentFactory[W], e: W)(
        implicit format: RootJsonFormat[W], ma: Manifest[W], transid: TransactionId) = {
        accessControl.checkAccessAndPutEntity(
            who,
            kind,
            entityStore,
            FullyQualifiedEntityName(path, EntityName("a")),
            UpdateOnly) { pre =>
                pre shouldBe defined
                pre match {
                    case Some(WhiskAction(ns, n, e, p, l, v, u, a)) => Future.successful(WhiskAction(ns, n, e, version = v.upPatch).asInstanceOf[W])
                    case _ => Future.successful(e)
                }
            }
    }

    // create or update
    def put[W <: WhiskEntity](who: Identity, path: EntityPath, name: String, kind: DocumentFactory[W], e: W)(
        implicit format: RootJsonFormat[W], ma: Manifest[W], transid: TransactionId) = {
        accessControl.checkAccessAndPutEntity(
            who,
            kind,
            entityStore,
            FullyQualifiedEntityName(path, EntityName(name)),
            CreateOrUpdate) {
                case None => Future.successful(e)
                case Some(WhiskAction(ns, n, e, p, l, v, u, a)) => Future.successful(WhiskAction(ns, n, e, version = v.upPatch).asInstanceOf[W])
                case _ => Future.successful(e)
            }
    }
}
