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

import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json.JsObject
import spray.json.pimpString
import whisk.core.controller.WhiskRulesApi
import whisk.core.entity._
import whisk.core.entity.test.OldWhiskTrigger
import whisk.http.ErrorResponse
import scala.language.postfixOps
import whisk.core.entity.test.OldWhiskRule
import whisk.http.Messages

/**
 * Tests Rules API.
 *
 * Unit tests of the controller service as a standalone component.
 * These tests exercise a fresh instance of the service object in memory -- these
 * tests do NOT communication with a whisk deployment.
 *
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 * "using Specs2RouteTest DSL to chain HTTP requests for unit testing, as in ~>"
 */
@RunWith(classOf[JUnitRunner])
class RulesApiTests extends ControllerTestCommon with WhiskRulesApi {

    /** Rules API tests */
    behavior of "Rules API"

    val creds = WhiskAuth(Subject(), AuthKey()).toIdentity
    val namespace = EntityPath(creds.subject())
    def aname() = MakeName.next("rules_tests")
    def afullname(namespace: EntityPath, name: String) = FullyQualifiedEntityName(namespace, EntityName(name))
    val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
    val activeStatus = s"""{"status":"${Status.ACTIVE}"}""".parseJson.asJsObject
    val inactiveStatus = s"""{"status":"${Status.INACTIVE}"}""".parseJson.asJsObject
    val entityTooBigRejectionMessage = "request entity too large"
    val parametersLimit = Parameters.sizeLimit

    //// GET /rules
    it should "list rules by default/explicit namespace" in {
        implicit val tid = transid()

        val rules = (1 to 2).map { i =>
            WhiskRule(namespace, aname(), afullname(namespace, "bogus trigger"), afullname(namespace, "bogus action"))
        }.toList
        rules foreach { put(_) }
        waitOnView(ruleStore, WhiskRule, namespace, 2)
        Get(s"$collectionPath") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[JsObject]]
            rules.length should be(response.length)
            rules forall { r => response contains r.summaryAsJson } should be(true)
        }

        // it should "list trirulesggers with explicit namespace owned by subject" in {
        Get(s"/$namespace/${collection.path}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[JsObject]]
            rules.length should be(response.length)
            rules forall { r => response contains r.summaryAsJson } should be(true)
        }

        // it should "reject list rules with explicit namespace not owned by subject" in {
        val auser = WhiskAuth(Subject(), AuthKey()).toIdentity
        Get(s"/$namespace/${collection.path}") ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }
    }

    //?docs disabled
    ignore should "list rules by default namespace with full docs" in {
        implicit val tid = transid()

        val rules = (1 to 2).map { i =>
            WhiskRule(namespace, aname(), afullname(namespace, "bogus trigger"), afullname(namespace, "bogus action"))
        }.toList
        rules foreach { put(_) }
        waitOnView(ruleStore, WhiskRule, namespace, 2)
        Get(s"$collectionPath?docs=true") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[WhiskRule]]
            rules.length should be(response.length)
            rules forall { r => response contains r } should be(true)
        }
    }

    //// GET /rule/name
    it should "get rule by name in default/explicit namespace" in {
        implicit val tid = transid()

        val rule = WhiskRule(namespace, aname(), afullname(namespace, "bogus trigger"), afullname(namespace, "bogus action"))
        put(rule)
        Get(s"$collectionPath/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.INACTIVE))
        }

        // it should "get trigger by name in explicit namespace owned by subject" in
        Get(s"/$namespace/${collection.path}/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.INACTIVE))
        }

        // it should "reject get trigger by name in explicit namespace not owned by subject" in
        val auser = WhiskAuth(Subject(), AuthKey()).toIdentity
        Get(s"/$namespace/${collection.path}/${rule.name}") ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }
    }

    it should "reject get of non existent rule" in {
        implicit val tid = transid()

        Get(s"$collectionPath/xxx") ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    it should "get rule with active state in trigger" in {
        implicit val tid = transid()

        val rule = WhiskRule(namespace, EntityName("get_active_rule"), afullname(namespace, "get_active_rule trigger"), afullname(namespace, "an action"))
        val trigger = WhiskTrigger(rule.trigger.path, rule.trigger.name, rules = Some {
            Map(rule.fullyQualifiedName(false) -> ReducedRule(rule.action, Status.ACTIVE))
        })

        put(trigger)
        put(rule)

        Get(s"$collectionPath/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.ACTIVE))
        }
    }

    it should "get rule with no rule-entries in trigger" in {
        implicit val tid = transid()

        val trigger = WhiskTrigger(namespace, EntityName("get_rule_with_empty_trigger trigger"))
        val rule = WhiskRule(namespace, EntityName("get_rule_with_empty_trigger"), trigger.fullyQualifiedName(false), afullname(namespace, "an action"))

        put(trigger)
        put(rule)

        Get(s"$collectionPath/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.INACTIVE))
        }
    }

    it should "report Conflict if the name was of a different type" in {
        implicit val tid = transid()

        val trigger = WhiskTrigger(namespace, aname())

        put(trigger)

        Get(s"/$namespace/${collection.path}/${trigger.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
        }
    }

    // DEL /rules/name
    it should "reject delete rule in state active" in {
        implicit val tid = transid()

        val rule = WhiskRule(namespace, EntityName("reject_delete_rule_active"), FullyQualifiedEntityName(namespace, aname()), afullname(namespace, "an action"))
        val trigger = WhiskTrigger(namespace, rule.trigger.name, rules = Some {
            Map(rule.fullyQualifiedName(false) -> ReducedRule(rule.action, Status.ACTIVE))
        })

        put(trigger)
        put(rule)

        Delete(s"$collectionPath/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
            val response = responseAs[ErrorResponse]
            response.error should be(s"rule status is '${Status.ACTIVE}', must be '${Status.INACTIVE}' to delete")
            response.code() should be >= 1L
        }
    }

    it should "delete rule in state inactive" in {
        implicit val tid = transid()

        val rule = WhiskRule(namespace, aname(), FullyQualifiedEntityName(namespace, aname()), FullyQualifiedEntityName(namespace, aname()))
        val triggerLink = ReducedRule(rule.action, Status.INACTIVE)
        val trigger = WhiskTrigger(rule.trigger.path, rule.trigger.name, rules = Some(Map(rule.fullyQualifiedName(false) -> triggerLink)))

        put(trigger, false)
        put(rule)

        Delete(s"$collectionPath/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            val t = get(trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)

            status should be(OK)
            t.rules.get.get(rule.fullyQualifiedName(false)) shouldBe None
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.INACTIVE))
        }
    }

    it should "delete rule in state inactive even if the trigger has been deleted" in {
        implicit val tid = transid()

        val rule = WhiskRule(namespace, EntityName("delete_rule_inactive"), afullname(namespace, "a trigger"), afullname(namespace, "an action"))

        put(rule)

        Delete(s"$collectionPath/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.INACTIVE))
        }
    }

    it should "delete rule in state inactive even if the trigger has no reference to the rule" in {
        implicit val tid = transid()

        val rule = WhiskRule(namespace, aname(), FullyQualifiedEntityName(namespace, aname()), afullname(namespace, "an action"))
        val trigger = WhiskTrigger(rule.trigger.path, rule.trigger.name)

        put(trigger, false)
        put(rule)

        Delete(s"$collectionPath/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            deleteTrigger(trigger.docid)

            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.INACTIVE))
        }
    }

    //// PUT /rules/name
    it should "create rule" in {
        implicit val tid = transid()

        val rule = WhiskRule(namespace, aname(), FullyQualifiedEntityName(namespace, aname()), FullyQualifiedEntityName(namespace, aname()))
        val trigger = WhiskTrigger(rule.trigger.path, rule.trigger.name)
        val action = WhiskAction(rule.action.path, rule.action.name, Exec.js("??"))
        val content = WhiskRulePut(Some(rule.trigger), Some(rule.action))

        put(trigger, false)
        put(action)

        Put(s"$collectionPath/${rule.name}", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)
            deleteRule(rule.docid)

            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.ACTIVE))
            t.rules.get(rule.fullyQualifiedName(false)) shouldBe ReducedRule(action.fullyQualifiedName(false), Status.ACTIVE)
        }
    }

    it should "create rule with an action in a package" in {
        implicit val tid = transid()

        val provider = WhiskPackage(namespace, aname(), publish = true)
        val action = WhiskAction(provider.path, aname(), Exec.js("??"))
        val trigger = WhiskTrigger(namespace, aname())
        val rule = WhiskRule(namespace, aname(), trigger.fullyQualifiedName(false), action.fullyQualifiedName(false))
        val content = WhiskRulePut(Some(rule.trigger), Some(rule.action))

        put(provider)
        put(trigger, false)
        put(action)

        Put(s"$collectionPath/${rule.name}", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)
            deleteRule(rule.docid)

            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.ACTIVE))
            t.rules.get(rule.fullyQualifiedName(false)) shouldBe ReducedRule(action.fullyQualifiedName(false), Status.ACTIVE)
        }
    }

    it should "reject create rule with annotations which are too big" in {
        implicit val tid = transid()

        val trigger = WhiskTrigger(namespace, aname())
        val action = WhiskAction(namespace, aname(), Exec.js("??"))

        val keys: List[Long] = List.range(Math.pow(10, 9) toLong, (Parameters.sizeLimit.toBytes / 2 / 20 + Math.pow(10, 9) + 2) toLong)
        val parameters = keys map { key =>
            Parameters(key.toString, "a" * 10)
        } reduce (_ ++ _)
        val content = s"""{"trigger":"${trigger.name}","action":"${action.name}","annotations":$parameters}""".parseJson.asJsObject

        put(trigger, false)
        put(action)

        Put(s"$collectionPath/${aname()}", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)

            status should be(RequestEntityTooLarge)
            response.entity.toString should include(entityTooBigRejectionMessage)
        }
    }

    it should "reject update rule with annotations which are too big" in {
        implicit val tid = transid()

        val trigger = WhiskTrigger(namespace, aname())
        val action = WhiskAction(namespace, aname(), Exec.js("??"))
        val rule = WhiskRule(namespace, aname(), trigger.fullyQualifiedName(false), action.fullyQualifiedName(false))

        val keys: List[Long] = List.range(Math.pow(10, 9) toLong, (Parameters.sizeLimit.toBytes / 2 / 20 + Math.pow(10, 9) + 2) toLong)
        val parameters = keys map { key =>
            Parameters(key.toString, "a" * 10)
        } reduce (_ ++ _)
        val content = s"""{"trigger":"${trigger.name}","action":"${action.name}","annotations":$parameters}""".parseJson.asJsObject

        put(trigger, false)
        put(action)
        put(rule)

        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)

            status should be(RequestEntityTooLarge)
            response.entity.toString should include(entityTooBigRejectionMessage)
        }
    }

    it should "reject rule if action does not exist" in {
        implicit val tid = transid()

        val trigger = WhiskTrigger(namespace, aname())
        val content = WhiskRulePut(Some(trigger.fullyQualifiedName(false)), Some(afullname(namespace, "bogus action")))

        put(trigger)

        Put(s"$collectionPath/xxx", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String] === s"${content.action.get.qualifiedNameWithLeadingSlash} does not exist"
        }
    }

    it should "reject rule if trigger does not exist" in {
        implicit val tid = transid()

        val action = WhiskAction(namespace, aname(), Exec.js("??"))
        val content = WhiskRulePut(Some(afullname(namespace, "bogus trigger")), Some(action.fullyQualifiedName(false)))

        put(action)

        Put(s"$collectionPath/xxx", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String] === s"${content.trigger.get.qualifiedNameWithLeadingSlash} does not exist"
        }
    }

    it should "reject rule if neither action or trigger do not exist" in {
        implicit val tid = transid()

        val content = WhiskRulePut(Some(afullname(namespace, "bogus trigger")), Some(afullname(namespace, "bogus action")))

        Put(s"$collectionPath/xxx", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String].contains("does not exist") should be(true)
        }
    }

    it should "update rule updating trigger and action at once" in {
        implicit val tid = transid()

        val trigger = WhiskTrigger(namespace, aname())
        val action = WhiskAction(namespace, aname(), Exec.js("??"))
        val rule = WhiskRule(namespace, aname(), afullname(namespace, "bogus trigger"), afullname(namespace, "bogus action"))
        val content = WhiskRulePut(Some(trigger.fullyQualifiedName(false)), Some(action.fullyQualifiedName(false)))

        put(trigger, false)
        put(action)
        put(rule, false)

        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)
            deleteRule(rule.docid)

            status should be(OK)

            t.rules.get(rule.fullyQualifiedName(false)).action should be(action.fullyQualifiedName(false))
            val response = responseAs[WhiskRuleResponse]
            response should be(WhiskRuleResponse(namespace, rule.name, Status.ACTIVE, trigger.fullyQualifiedName(false), action.fullyQualifiedName(false), version = SemVer().upPatch))
        }
    }

    it should "update rule with a new action while passing the same trigger" in {
        implicit val tid = transid()

        val trigger = WhiskTrigger(namespace, aname())
        val action = WhiskAction(namespace, aname(), Exec.js("??"))
        val rule = WhiskRule(namespace, aname(), trigger.fullyQualifiedName(false), afullname(namespace, "bogus action"))
        val content = WhiskRulePut(Some(trigger.fullyQualifiedName(false)), Some(action.fullyQualifiedName(false)))

        put(trigger, false)
        put(action)
        put(rule, false)

        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)
            deleteRule(rule.docid)

            status should be(OK)
            t.rules.get(rule.fullyQualifiedName(false)).action should be(action.fullyQualifiedName(false))
            val response = responseAs[WhiskRuleResponse]
            response should be(WhiskRuleResponse(namespace, rule.name, Status.ACTIVE, trigger.fullyQualifiedName(false), action.fullyQualifiedName(false), version = SemVer().upPatch))
        }
    }

    it should "update rule with just a new action" in {
        implicit val tid = transid()

        val trigger = WhiskTrigger(namespace, aname())
        val action = WhiskAction(namespace, aname(), Exec.js("??"))
        val rule = WhiskRule(namespace, aname(), trigger.fullyQualifiedName(false), afullname(namespace, "bogus action"))
        val content = WhiskRulePut(action = Some(action.fullyQualifiedName(false)))

        put(trigger, false)
        put(action)
        put(rule, false)

        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)
            deleteRule(rule.docid)

            status should be(OK)
            t.rules.get(rule.fullyQualifiedName(false)).action should be(action.fullyQualifiedName(false))
            val response = responseAs[WhiskRuleResponse]
            response should be(WhiskRuleResponse(namespace, rule.name, Status.ACTIVE, trigger.fullyQualifiedName(false), action.fullyQualifiedName(false), version = SemVer().upPatch))
        }
    }

    it should "update rule with just a new trigger" in {
        implicit val tid = transid()

        val trigger = WhiskTrigger(namespace, aname())
        val action = WhiskAction(namespace, aname(), Exec.js("??"))
        val rule = WhiskRule(namespace, aname(), trigger.fullyQualifiedName(false), action.fullyQualifiedName(false))
        val content = WhiskRulePut(trigger = Some(trigger.fullyQualifiedName(false)))

        put(trigger, false)
        put(action)
        put(rule, false)

        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)
            deleteRule(rule.docid)

            status should be(OK)
            t.rules.get.get(rule.fullyQualifiedName(false)) shouldBe a[Some[_]]
            val response = responseAs[WhiskRuleResponse]
            response should be(WhiskRuleResponse(namespace, rule.name, Status.ACTIVE, trigger.fullyQualifiedName(false), action.fullyQualifiedName(false), version = SemVer().upPatch))
        }
    }

    it should "update rule when no new content is provided" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname())
        val action = WhiskAction(namespace, aname(), Exec.js("??"))
        val rule = WhiskRule(namespace, aname(), trigger.fullyQualifiedName(false), action.fullyQualifiedName(false))
        val content = WhiskRulePut(None, None, None, None, None)

        put(trigger, false)
        put(action)
        put(rule, false)

        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            deleteTrigger(trigger.docid)
            deleteRule(rule.docid)

            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(WhiskRuleResponse(namespace, rule.name, Status.ACTIVE, trigger.fullyQualifiedName(false), action.fullyQualifiedName(false), version = SemVer().upPatch))
        }
    }

    it should "reject update rule if trigger does not exist" in {
        implicit val tid = transid()

        val action = WhiskAction(namespace, aname(), Exec.js("??"))
        val rule = WhiskRule(namespace, aname(), afullname(namespace, "bogus trigger"), action.fullyQualifiedName(false))
        val content = WhiskRulePut(action = Some(action.fullyQualifiedName(false)))

        put(action)
        put(rule)

        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String] === s"${rule.trigger.qualifiedNameWithLeadingSlash} does not exist"
        }
    }

    it should "reject update rule if action does not exist" in {
        implicit val tid = transid()

        val trigger = WhiskTrigger(namespace, aname())
        val rule = WhiskRule(namespace, aname(), trigger.fullyQualifiedName(false), afullname(namespace, "bogus action"))
        val content = WhiskRulePut(trigger = Some(trigger.fullyQualifiedName(false)))

        put(trigger)
        put(rule)

        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String] === s"${rule.action.qualifiedNameWithLeadingSlash} does not exist"
        }
    }

    it should "reject update rule if neither trigger or action exist" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, aname(), afullname(namespace, "bogus trigger"), afullname(namespace, "bogus action"))
        val content = WhiskRulePut(None, None, None, None, None)

        put(rule)

        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String] should {
                include(s"${rule.action.qualifiedNameWithLeadingSlash} does not exist") or
                    include(s"${rule.trigger.qualifiedNameWithLeadingSlash} does not exist")
            }
        }
    }

    it should "reject update rule in state active" in {
        implicit val tid = transid()

        val rule = WhiskRule(namespace, aname(), afullname(namespace, "a trigger"), afullname(namespace, "an action"))
        val trigger = WhiskTrigger(namespace, rule.trigger.name, rules = Some {
            Map(rule.fullyQualifiedName(false) -> ReducedRule(rule.action, Status.ACTIVE))
        })
        val content = WhiskRulePut(publish = Some(!rule.publish))

        put(trigger)
        put(rule)

        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
        }
    }

    //// POST /rules/name
    it should "do nothing to disable already disabled rule" in {
        implicit val tid = transid()

        val rule = WhiskRule(namespace, aname(), afullname(namespace, "a trigger"), afullname(namespace, "an action"))

        put(rule)

        Post(s"$collectionPath/${rule.name}", inactiveStatus) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
        }
    }

    it should "do nothing to enable already enabled rule" in {
        implicit val tid = transid()

        val rule = WhiskRule(namespace, aname(), afullname(namespace, aname().name), afullname(namespace, "an action"))
        val trigger = WhiskTrigger(namespace, rule.trigger.name, rules = Some {
            Map(rule.fullyQualifiedName(false) -> ReducedRule(rule.action, Status.ACTIVE))
        })

        put(trigger)
        put(rule)

        Post(s"$collectionPath/${rule.name}", activeStatus) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
        }
    }

    it should "reject post with status undefined" in {
        implicit val tid = transid()

        Post(s"$collectionPath/xyz") ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    it should "reject post with invalid status" in {
        implicit val tid = transid()

        val badStatus = s"""{"status":"xxx"}""".parseJson.asJsObject

        Post(s"$collectionPath/xyz", badStatus) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    it should "activate rule" in {
        implicit val tid = transid()

        val rule = WhiskRule(namespace, aname(), afullname(namespace, aname().name), afullname(namespace, "an action"))
        val trigger = WhiskTrigger(namespace, rule.trigger.name, rules = Some {
            Map(rule.fullyQualifiedName(false) -> ReducedRule(rule.action, Status.INACTIVE))
        })

        put(trigger, false)
        put(rule)

        Post(s"$collectionPath/${rule.name}", activeStatus) ~> sealRoute(routes(creds)) ~> check {
            val t = get(trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)

            status should be(OK)

            t.rules.get(rule.fullyQualifiedName(false)).status should be(Status.ACTIVE)
        }
    }

    it should "activate rule without rule in trigger" in {
        implicit val tid = transid()

        val rule = WhiskRule(namespace, aname(), afullname(namespace, aname().name), afullname(namespace, "an action"))
        val trigger = WhiskTrigger(namespace, rule.trigger.name)

        put(trigger, false)
        put(rule)

        Post(s"$collectionPath/${rule.name}", activeStatus) ~> sealRoute(routes(creds)) ~> check {
            val t = get(trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)

            status should be(OK)
            t.rules.get(rule.fullyQualifiedName(false)).status should be(Status.ACTIVE)
        }
    }

    it should "reject rule activation, if the trigger is absent" in {
        implicit val tid = transid()

        val rule = WhiskRule(namespace, aname(), afullname(namespace, aname().name), afullname(namespace, "an action"))

        put(rule)

        Post(s"$collectionPath/${rule.name}", activeStatus) ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    it should "deactivate rule" in {
        implicit val tid = transid()

        val rule = WhiskRule(namespace, aname(), afullname(namespace, aname().name), afullname(namespace, "an action"))
        val trigger = WhiskTrigger(namespace, rule.trigger.name, rules = Some {
            Map(rule.fullyQualifiedName(false) -> ReducedRule(rule.action, Status.ACTIVE))
        })

        put(trigger, false)
        put(rule)

        Post(s"$collectionPath/${rule.name}", inactiveStatus) ~> sealRoute(routes(creds)) ~> check {
            val t = get(trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)

            status should be(OK)
            t.rules.get(rule.fullyQualifiedName(false)).status should be(Status.INACTIVE)
        }
    }

    // invalid resource
    it should "reject invalid resource" in {
        implicit val tid = transid()

        val rule = WhiskRule(namespace, aname(), afullname(namespace, "a trigger"), afullname(namespace, "an action"))

        put(rule)

        Get(s"$collectionPath/${rule.name}/bar") ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    // migration path
    it should "handle a rule with the old schema gracefully" in {
        implicit val tid = transid()

        val rule = OldWhiskRule(namespace, aname(), EntityName("a trigger"), EntityName("an action"), Status.ACTIVE)

        put(rule)

        Get(s"$collectionPath/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.toWhiskRule.withStatus(Status.INACTIVE))
        }
    }

    it should "create rule even if the attached trigger has the old schema" in {
        implicit val tid = transid()

        val rule = WhiskRule(namespace, aname(), afullname(namespace, aname().name), afullname(namespace, aname().name))
        val trigger = OldWhiskTrigger(namespace, rule.trigger.name)

        val action = WhiskAction(namespace, rule.action.name, Exec.js("??"))
        val content = WhiskRulePut(Some(rule.trigger), Some(rule.action))

        put(trigger, false)
        put(action)

        Put(s"$collectionPath/${rule.name}", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)
            deleteRule(rule.docid)

            status should be(OK)
            t.rules.get(rule.fullyQualifiedName(false)) shouldBe ReducedRule(action.fullyQualifiedName(false), Status.ACTIVE)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.ACTIVE))
        }
    }

    it should "activate rule even if it is still in the old schema" in {
        implicit val tid = transid()

        val ruleNameQualified = FullyQualifiedEntityName(namespace, aname())
        val triggerName = aname()
        val actionName = FullyQualifiedEntityName(namespace, aname())
        val rule = OldWhiskRule(namespace, ruleNameQualified.name, triggerName, actionName.name, Status.ACTIVE)
        val trigger = WhiskTrigger(namespace, triggerName, rules = Some(Map(ruleNameQualified -> ReducedRule(actionName, Status.INACTIVE))))

        put(trigger, false)
        put(rule)

        Post(s"$collectionPath/${rule.name}", activeStatus) ~> sealRoute(routes(creds)) ~> check {
            val t = get(ruleStore, trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)

            status should be(OK)

            t.rules.get(ruleNameQualified).status should be(Status.ACTIVE)
        }
    }

    it should "report proper error when record is corrupted on delete" in {
        implicit val tid = transid()
        val entity = BadEntity(namespace, aname())
        put(entity)

        Delete(s"$collectionPath/${entity.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(InternalServerError)
            responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
        }
    }

    it should "report proper error when record is corrupted on get" in {
        implicit val tid = transid()
        val entity = BadEntity(namespace, aname())
        put(entity)

        Get(s"$collectionPath/${entity.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(InternalServerError)
            responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
        }
    }

    it should "report proper error when record is corrupted on put" in {
        implicit val tid = transid()
        val entity = BadEntity(namespace, aname())
        put(entity)

        val content = WhiskRulePut()
        Put(s"$collectionPath/${entity.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(InternalServerError)
            responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
        }
    }

    it should "report proper error when action record is corrupted on put" in {
        implicit val tid = transid()
        val tentity = BadEntity(namespace, aname())
        val aentity = BadEntity(namespace, aname())
        val rule = WhiskRule(namespace, aname(), afullname(namespace, aname().name), afullname(namespace, aname().name))
        val trigger = WhiskTrigger(namespace, rule.trigger.name)
        val action = WhiskAction(namespace, rule.action.name, Exec.js("??"))

        val contenta = WhiskRulePut(Some(tentity.fullyQualifiedName(false)), Some(aentity.fullyQualifiedName(false)))
        val contentb = WhiskRulePut(Some(trigger.fullyQualifiedName(false)), Some(aentity.fullyQualifiedName(false)))
        val contentc = WhiskRulePut(Some(tentity.fullyQualifiedName(false)), Some(action.fullyQualifiedName(false)))

        put(tentity)
        put(aentity)
        put(trigger)
        put(action)

        Put(s"$collectionPath/${aname()}", contenta) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
        }

        Put(s"$collectionPath/${aname()}", contentb) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
        }

        Put(s"$collectionPath/${aname()}", contentc) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
        }
    }
}
