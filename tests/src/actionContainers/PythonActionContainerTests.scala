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

package actionContainers

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import spray.json._

import ActionContainer.withContainer

import scala.util.Random

@RunWith(classOf[JUnitRunner])
class PythonActionContainerTests extends ActionProxyContainerTestUtils {

    def withPythonContainer(code: ActionContainer => Unit) = withContainer("whisk/pythonaction")(code)

    behavior of "whisk/pythonaction"

    it should "support valid json" in {
        val (out, err) = withPythonContainer { c =>
            val code = """
                |def main(dict):
                |    return dict
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)

            val argss = List(
                JsObject("user" -> JsNull),
                JsObject("user" -> JsString("Momo")))

            for (args <- argss) {
                val (runCode, out) = c.run(runPayload(args))
                runCode should be(200)
                out should be(Some(args))
            }
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
    }

    it should "return on action error when action fails" in {
        val (out, err) = withPythonContainer { c =>
            val code = """
                |def div(x, y):
                |    return x/y
                |
                |def main(dict):
                |    return {"divBy0": div(5,0)}
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(502)

            runRes shouldBe defined
            runRes.get.fields.get("error") shouldBe defined
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e should include("Traceback")
        })
    }

    it should "log compilation errors" in {
        val (out, err) = withPythonContainer { c =>
            val code = """
              | 10 PRINT "Hello!"
              | 20 GOTO 10
            """.stripMargin

            val (initCode, res) = c.init(initPayload(code))
            // init checks whether compilation was successful, so return 502
            initCode should be(502)

            val (runCode, runRes) = c.run(runPayload(JsObject("basic" -> JsString("forever"))))
            runCode should be(502)
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e should include("Traceback")
        })
    }

    it should "support application errors" in {
        val (out, err) = withPythonContainer { c =>
            val code = """
                |def main(args):
                |    return { "error": "sorry" }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(200) // action writer returning an error is OK

            runRes shouldBe defined
            runRes should be(Some(JsObject("error" -> JsString("sorry"))))
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
    }

    it should "enforce that action returns a dictionary" in {
        val (out, err) = withPythonContainer { c =>
            val code = """
                |def main(args):
                |    return "rebel"
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(502)
            runRes should be(Some(JsObject("error" -> JsString("The action did not return a dictionary."))))
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
    }
}
