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

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import common.WskActorSystem
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionCounter
import whisk.core.database.DocumentSerializer
import whisk.core.entity.DocId

@RunWith(classOf[JUnitRunner])
class MockDocumentFactoryTests extends FlatSpec
    with Matchers
    with WskActorSystem
    with TransactionCounter {

    case class Doc(id: String) extends DocumentSerializer {
        def docid = DocId(id)
        def toDocumentRecord = JsObject("id" -> JsString(id), "_id" -> JsString(id))
    }

    object Doc extends DefaultJsonProtocol {
        val serdes = jsonFormat1(Doc.apply)
    }

    val entityStore = new MockDocumentFactory[Doc](Doc.serdes)

    behavior of "MockDocumentFactory"

    it should "sanity check" in {
        implicit val tid = transid()
        val d = Doc("a")
        entityStore.contains(d.docid) shouldBe false
        Await.result(entityStore.put(d), 1.second)
        entityStore.contains(d.docid) shouldBe true
        Await.result(entityStore.get[Doc](d.docid.asDocInfo), 1.second) shouldBe d
        Await.result(entityStore.del(d.docid.asDocInfo), 1.second) shouldBe true
        entityStore.isEmpty shouldBe true
    }
}
