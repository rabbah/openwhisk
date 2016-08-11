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
import spray.json.JsNull
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsString

@RunWith(classOf[JUnitRunner])
class ActionProxyContainerTests extends ActionProxyContainerTestUtils {

    def withPythonContainer(code: ActionContainer => Unit) = withContainer("whisk/dockerskeleton")(code)

    behavior of "whisk/dockerskeleton"

    it should "run sample without init" in {
        val (out, err) = withPythonContainer { c =>
            val (runCode, out) = c.run(JsNull)
            runCode should be(200)
            out should be(Some(JsObject("msg" -> JsString("Hello from bash script!"))))
        }

        checkStreams(out, err, {
            case (o, _) => o should include("Actions may log to stdout")
        })
    }

    it should "run sample with init that does nothing" in {
        val (out, err) = withPythonContainer { c =>
            val (initCode, _) = c.init(JsNull)
            initCode should be(200)
            val (runCode, out) = c.run(JsNull)
            runCode should be(200)
            out should be(Some(JsObject("msg" -> JsString("Hello from bash script!"))))
        }
        checkStreams(out, err, {
            case (o, _) => o should include("Actions may log to stdout")
        })
    }

    it should "respond with 404 for bad run argument" in {
        val (out, err) = withPythonContainer { c =>
            val (runCode, out) = c.run(runPayload(JsString("A")))
            runCode should be(404)
        }
        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
    }

    it should "fail to run a bad script" in {
        val (out, err) = withPythonContainer { c =>
            val (initCode, _) = c.init(initPayload(""))
            initCode should be(200)
            val (runCode, out) = c.run(JsNull)
            runCode should be(502)
            out should be(Some(JsObject("error" -> JsString("The action did not return a dictionary."))))
        }
        checkStreams(out, err, {
            case (o, _) => o should include("error")
        })
    }

    it should "run and report an error for script not returning a json object" in {
        val (out, err) = withPythonContainer { c =>
            val code = """
                |#!/bin/sh
                |echo not a json object
            """.stripMargin.trim

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)
            val (runCode, out) = c.run(JsNull)
            runCode should be(502)
            out should be(Some(JsObject("error" -> JsString("The action did not return a dictionary."))))
        }

        checkStreams(out, err, {
            case (o, e) => o should include("not a json object")
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
                out should be(Some(args))
            }
        }
        checkStreams(out, err, {
            case (o, _) => o should include("Actions may log to stdout")
        })
    }

    it should "run a new python script" in {
        val (out, err) = withPythonContainer { c =>
            val code = """
                |#!/usr/bin/env python
                |import sys
                |print('hello')
                |print(sys.argv[1])
            """.stripMargin.trim

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)

            val argss = List(
                JsObject("a" -> JsString("A")),
                JsObject("i" -> JsNumber(1)))

            for (args <- argss) {
                val (runCode, out) = c.run(runPayload(args))
                runCode should be(200)
                out should be(Some(args))
            }
        }
        checkStreams(out, err, {
            case (o, _) => o should include("hello")
        })
    }

    it should "run a new perl script" in {
        val (out, err) = withPythonContainer { c =>
            val code = """
                |#!/usr/bin/env perl
                |print "hello\n";
                |print $ARGV[0];
            """.stripMargin.trim

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)

            val argss = List(
                JsObject("a" -> JsString("A")),
                JsObject("i" -> JsNumber(1)))

            for (args <- argss) {
                val (runCode, out) = c.run(runPayload(args))
                runCode should be(200)
                out should be(Some(args))
            }
        }
        checkStreams(out, err, {
            case (o, _) => o should include("hello")
        })
    }
}
