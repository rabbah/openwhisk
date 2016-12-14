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

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.http.scaladsl.model._
import akka.stream.scaladsl._
import akka.util.ByteString
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionId
import whisk.core.database._
import whisk.core.entity.DocId
import whisk.core.entity.DocInfo
import whisk.core.entity.WhiskEntityJsonFormat

class MockDocumentFactory[W <: DocumentSerializer](docDeserializer: RootJsonFormat[W])(
    override implicit val executionContext: ExecutionContext)
    extends ArtifactStore[W] {

    protected val mockstore = scala.collection.mutable.Map[String, JsObject]()

    def contains(docid: DocId) = mockstore.get(docid.asString).isDefined
    def isEmpty = mockstore.isEmpty

    protected[database] override def put(d: W)(
        implicit transid: TransactionId): Future[DocInfo] = {
        val asJson = d.toDocumentRecord
        val id: String = asJson.fields("_id").convertTo[String].trim
        mockstore.put(id, asJson)
        Future.successful(DocInfo(id))
    }

    protected[database] override def del(d: DocInfo)(
        implicit transid: TransactionId): Future[Boolean] = {
        Future.successful {
            mockstore.remove(d.id.asString).map(_ => true).getOrElse(false)
        }
    }

    override protected[database] def get[A <: W](doc: DocInfo)(
        implicit transid: TransactionId,
        ma: Manifest[A]): Future[A] = {
        mockstore.get(doc.id.asString).map {
            doc =>
                val asFormat = docDeserializer.read(doc)
                if (asFormat.getClass == ma.runtimeClass) {
                    val deserialized = asFormat.asInstanceOf[A]
                    Future.successful(deserialized)
                } else {
                    Future.failed(DocumentTypeMismatchException(s"document type ${asFormat.getClass} did not match expected type ${ma.runtimeClass}."))
                }
        } getOrElse {
            Future.failed(NoDocumentException("document does not exist."))
        }
    }

    override protected[core] def query(table: String, startKey: List[Any], endKey: List[Any], skip: Int, limit: Int, includeDocs: Boolean, descending: Boolean, reduce: Boolean)(
        implicit transid: TransactionId): Future[List[JsObject]] = {
        ???
    }

    override protected[core] def attach(doc: DocInfo, name: String, contentType: ContentType, docStream: Source[ByteString, _])(
        implicit transid: TransactionId): Future[DocInfo] = {
        ???
    }

    override protected[core] def readAttachment[T](doc: DocInfo, name: String, sink: Sink[ByteString, Future[T]])(
        implicit transid: TransactionId): Future[(ContentType, T)] = {
        ???
    }

    override def shutdown(): Unit = {}
}
