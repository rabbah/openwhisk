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

package whisk.core.database.test

import java.util.concurrent.TimeoutException

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionCounter
import whisk.common.TransactionId
import whisk.core.database._
import whisk.core.entity._
import whisk.core.entity.types.AuthStore
import whisk.core.entity.types.EntityStore

trait DbUtils extends TransactionCounter {
    implicit val dbOpTimeout = 15 seconds
    val docsToDelete = ListBuffer[(ArtifactStore[_], DocInfo)]()
    case class RetryOp() extends Throwable

    /**
     * Retry an operation 'step()' awaiting its result up to 'timeout'.
     * Attempt the operation up to 'count' times. The future from the
     * step is not aborted --- TODO fix this.
     */
    def retry[T](step: () => Future[T], timeout: Duration, count: Int = 5): Try[T] = {
        val future = step()
        if (count > 0) try {
            val result = Await.result(future, timeout)
            Success(result)
        } catch {
            case n: NoDocumentException =>
                println("no document exception, retrying")
                retry(step, timeout, count - 1)
            case RetryOp() =>
                println("condition not met, retrying")
                retry(step, timeout, count - 1)
            case t: TimeoutException =>
                println("timed out, retrying")
                retry(step, timeout, count - 1)
            case t: Throwable =>
                println(s"unexpected failure $t")
                Failure(t)
        }
        else Failure(new NoDocumentException("timed out"))
    }

    /**
     * Wait on a view to update with documents added to namespace. This uses retry above,
     * where the step performs a direct db query to retrieve the view and check the count
     * matches the given value.
     */
    def waitOnView[A](db: ArtifactStore[A], namespace: EntityPath, count: Int)(
        implicit context: ExecutionContext, transid: TransactionId, timeout: Duration) = {
        val success = retry(() => {
            val startKey = List(namespace.toString)
            val endKey = List(namespace.toString, WhiskEntityQueries.TOP)
            db.query(WhiskEntityQueries.viewname(WhiskEntityQueries.ALL), startKey, endKey, 0, 0, false, true, false) map { l =>
                if (l.length != count) {
                    throw RetryOp()
                } else true
            }
        }, timeout)
        assert(success.isSuccess, "wait aborted")
    }

    /**
     * Wait on a view specific to a collection to update with documents added to that collection in namespace.
     * This uses retry above, where the step performs a collection-specific view query using the collection
     * factory. The result count from the view is checked against the given value.
     */
    def waitOnView[A <: WhiskEntity](db: ArtifactStore[A], factory: WhiskEntityQueries[_], namespace: EntityPath, count: Int)(
        implicit context: ExecutionContext, transid: TransactionId, timeout: Duration) = {
        val success = retry(() => {
            factory.listCollectionInNamespace(db, namespace, 0, 0) map { l =>
                if (l.left.get.length < count) {
                    throw RetryOp()
                } else true
            }
        }, timeout)
        assert(success.isSuccess, "wait aborted")
    }

    /**
     * Wait on view for the authentication table. This is like the other waitOnViews but
     * specific to the WhiskAuth records.
     */
    def waitOnView(db: AuthStore, uuid: UUID, count: Int)(
        implicit context: ExecutionContext, transid: TransactionId, timeout: Duration) = {
        val success = retry(() => {
            WhiskAuth.list(db, uuid) map { l =>
                if (l.length != count) {
                    throw RetryOp()
                } else true
            }
        }, timeout)
        assert(success.isSuccess, "wait aborted after: " + timeout + ": " + success)
    }

    /**
     * Wait on view using the CouchDbRestClient. This is like the other waitOnViews.
     */
    def waitOnView(db: CouchDbRestClient, designDocName: String, viewName: String, count: Int)(
        implicit context: ExecutionContext, timeout: Duration) = {
        val success = retry(() => {
            db.executeView(designDocName, viewName)().map {
                case Right(doc) =>
                    val length = doc.fields("rows").convertTo[List[JsObject]].length
                    if (length != count) {
                        throw RetryOp()
                    } else true
                case Left(_) =>
                    throw RetryOp()
            }
        }, timeout)
        assert(success.isSuccess, "wait aborted after: " + timeout + ": " + success)
    }

    /**
     * Puts document 'w' in datastore, and add it to gc queue to delete after the test completes.
     */
    def put[A](w: A, garbageCollect: Boolean = true)(
        implicit transid: TransactionId, timeout: Duration = 10 seconds, db: ArtifactStore[A], ev: A => DocumentSerializer): DocInfo = {
        val docFuture = db.put(w)
        val doc = Await.result(docFuture, timeout)
        assert(doc != null)
        if (garbageCollect) docsToDelete += ((db, doc))
        doc
    }

    /**
     * Gets document by id from datastore, and add it to gc queue to delete after the test completes.
     */
    def get[A](docid: DocId, factory: DocumentFactory[A], garbageCollect: Boolean = true)(
        implicit transid: TransactionId, timeout: Duration = 10 seconds, db: ArtifactStore[A]): A = {
        val docFuture = factory.get(db, docid)
        val doc = Await.result(docFuture, timeout)
        assert(doc != null)
        if (garbageCollect) docsToDelete += ((db, docid.asDocInfo))
        doc
    }

    /**
     * Deletes document by id from datastore.
     */
    def del[A <: WhiskDocument](docid: DocId, factory: DocumentFactory[A])(
        implicit transid: TransactionId, timeout: Duration = 10 seconds, db: ArtifactStore[A]) = {
        val docFuture = factory.get(db, docid)
        val doc = Await.result(docFuture, timeout)
        assert(doc != null)
        Await.result(db.del(doc.docinfo), timeout)
    }

    /**
     * Puts a document 'entity' into the datastore, then do a get to retrieve it and confirm the identity.
     */
    def putGetCheck[A](entity: A, factory: DocumentFactory[A], gc: Boolean = true)(
        implicit transid: TransactionId, timeout: Duration = 10 seconds, db: ArtifactStore[A], ev: A => DocumentSerializer): (DocInfo, A) = {
        val doc = put(entity, gc)
        assert(doc != null && doc.id() != null && doc.rev() != null)
        val future = factory.get(db, doc.id, doc.rev)
        val dbEntity = Await.result(future, timeout)
        assert(dbEntity != null)
        assert(dbEntity == entity)
        (doc, dbEntity)
    }

    /**
     * Deletes all documents added to gc queue.
     */
    def cleanup()(implicit timeout: Duration = 10 seconds) = {
        docsToDelete.map { e => Try(Await.result(e._1.del(e._2)(TransactionId.testing), timeout)) }
        docsToDelete.clear()
    }
}
