package dev.kuml.profile.soaml

import dev.kuml.profile.ProfileRegistry
import dev.kuml.profile.UmlMetaclass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * AP-4.5 profile tests for the SoaML profile.
 *
 * Covers:
 * 1. Whitelist — exactly 8 stereotypes matching the OMG SoaML core set
 * 2. Target metaclass checks for all 8 stereotypes
 * 3. ServiceLoader discovery via ProfileRegistry.loadFromClasspath()
 */
class SoamlProfileTest :
    FunSpec({

        beforeEach { ProfileRegistry.clear() }
        afterEach { ProfileRegistry.clear() }

        // ── Test 1: Whitelist — exactly 8 stereotypes ─────────────────────────────

        test("soamlProfile has exactly 8 stereotypes matching OMG SoaML core") {
            val names = soamlProfile.stereotypes.map { it.name }.toSet()
            names shouldContainExactlyInAnyOrder
                setOf(
                    "Participant",
                    "ServiceInterface",
                    "Service",
                    "Request",
                    "ServiceContract",
                    "ServicesArchitecture",
                    "ServiceChannel",
                    "MessageType",
                )
            soamlProfile.stereotypes.size shouldBe 8
        }

        // ── Test 2: Target metaclass checks ───────────────────────────────────────

        test("Participant stereotype targets UmlMetaclass.Class") {
            val s = soamlProfile.stereotype("Participant")
            s shouldNotBe null
            s!!.targetMetaclass shouldBe UmlMetaclass.Class
        }

        test("ServiceInterface stereotype targets UmlMetaclass.Interface") {
            val s = soamlProfile.stereotype("ServiceInterface")
            s shouldNotBe null
            s!!.targetMetaclass shouldBe UmlMetaclass.Interface
        }

        test("Service stereotype targets UmlMetaclass.Port") {
            val s = soamlProfile.stereotype("Service")
            s shouldNotBe null
            s!!.targetMetaclass shouldBe UmlMetaclass.Port
        }

        test("Request stereotype targets UmlMetaclass.Port") {
            val s = soamlProfile.stereotype("Request")
            s shouldNotBe null
            s!!.targetMetaclass shouldBe UmlMetaclass.Port
        }

        test("ServiceContract stereotype targets UmlMetaclass.Collaboration") {
            val s = soamlProfile.stereotype("ServiceContract")
            s shouldNotBe null
            s!!.targetMetaclass shouldBe UmlMetaclass.Collaboration
        }

        test("ServicesArchitecture stereotype targets UmlMetaclass.Collaboration") {
            val s = soamlProfile.stereotype("ServicesArchitecture")
            s shouldNotBe null
            s!!.targetMetaclass shouldBe UmlMetaclass.Collaboration
        }

        test("ServiceChannel stereotype targets UmlMetaclass.Connector") {
            val s = soamlProfile.stereotype("ServiceChannel")
            s shouldNotBe null
            s!!.targetMetaclass shouldBe UmlMetaclass.Connector
        }

        test("MessageType stereotype targets UmlMetaclass.Class") {
            val s = soamlProfile.stereotype("MessageType")
            s shouldNotBe null
            s!!.targetMetaclass shouldBe UmlMetaclass.Class
        }

        // ── Test 3: ServiceLoader discovery ───────────────────────────────────────

        test("soamlProfile is discovered via ProfileRegistry.loadFromClasspath") {
            ProfileRegistry.loadFromClasspath()
            val found = ProfileRegistry.get("dev.kuml.profiles.soaml")
            found shouldNotBe null
            found!!.name shouldBe "SoaML"
            found.stereotype("Participant") shouldNotBe null
            found.stereotype("ServiceContract") shouldNotBe null
        }

        // ── Test 4: Profile metadata ───────────────────────────────────────────────

        test("soamlProfile has correct namespace and version") {
            soamlProfile.namespace shouldBe "dev.kuml.profiles.soaml"
            soamlProfile.version shouldBe "1.0.0"
        }

        // ── Test 5: Participant constraint is present ─────────────────────────────

        test("Participant stereotype has participant-has-port OCL constraint") {
            val participant = soamlProfile.stereotype("Participant")
            participant shouldNotBe null
            val constraint = participant!!.constraints.find { it.name == "participant-has-port" }
            constraint shouldNotBe null
            constraint!!.body shouldBe "self.ownedPort->notEmpty()"
        }

        // ── Test 6: ServiceContract constraint is present ─────────────────────────

        test("ServiceContract stereotype has contract-has-two-roles OCL constraint") {
            val serviceContract = soamlProfile.stereotype("ServiceContract")
            serviceContract shouldNotBe null
            val constraint = serviceContract!!.constraints.find { it.name == "contract-has-two-roles" }
            constraint shouldNotBe null
            constraint!!.body shouldBe "self.role->size() >= 2"
        }
    })
