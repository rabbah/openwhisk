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
import org.scalatest.Finders
import org.scalatest.junit.JUnitRunner

import ActionContainer.withContainer
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsString

@RunWith(classOf[JUnitRunner])
class DockerExampleContainerTests extends ActionProxyContainerTestUtils {

    def withPythonContainer(code: ActionContainer => Unit) = withContainer("openwhisk/example")(code)

    behavior of "openwhisk/example"

    private def checkresponse(res: Option[JsObject], args: JsObject = JsObject()) = {
        res shouldBe defined
        res.get.fields("msg") shouldBe JsString("Hello from arbitrary C program!")
        res.get.fields("args") shouldBe args
    }

    it should "run sample without init" in {
        val (out, err) = withPythonContainer { c =>
            val (runCode, out) = c.run(JsObject())
            runCode should be(200)
            checkresponse(out)
        }

        checkStreams(out, err, {
            case (o, _) => o should include("This is an example log message from an arbitrary C program!")
        })
    }

    it should "run sample with init that does nothing" in {
        val (out, err) = withPythonContainer { c =>
            val (initCode, _) = c.init(JsObject())
            initCode should be(200)
            val (runCode, out) = c.run(JsObject())
            runCode should be(200)
            checkresponse(out)
        }

        checkStreams(out, err, {
            case (o, _) => o should include("This is an example log message from an arbitrary C program!")
        })
    }

    it should "run sample with argument" in {
        val (out, err) = withPythonContainer { c =>
            val argss = List(
                JsObject("a" -> JsString("A")),
                JsObject("i" -> JsNumber(1)))

            for (args <- argss) {
                val (runCode, out) = c.run(runPayload(args))
                runCode should be(200)
                checkresponse(out, args)
            }
        }

        checkStreams(out, err, {
            case (o, _) => o should include("This is an example log message from an arbitrary C program!")
        })
    }
}
