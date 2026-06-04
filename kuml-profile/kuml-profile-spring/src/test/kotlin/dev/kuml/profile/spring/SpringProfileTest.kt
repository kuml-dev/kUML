package dev.kuml.profile.spring

import dev.kuml.core.dsl.classDiagram
import dev.kuml.profile.ProfileRegistry
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.javaee.javaEeProfile
import dev.kuml.uml.UmlClass
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.operation
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * AP-5a.2 profile tests for the Spring profile.
 *
 * Covers:
 * 1. Whitelist — exactly 3 stereotypes (V1.1.2: +Scheduled)
 * 2. Profile extends JavaEE by namespace (D12)
 * 3. Stereotyp-Spezialisierung: RestController specializes Controller, SpringData specializes Repository
 * 4. ServiceLoader discovery
 * 5. Vererbungs-Test: springProfile alone does not contain Entity/Controller
 * 6. Properties on Spring stereotypes
 * 7. V1.1.2: Scheduled stereotype tests
 */
class SpringProfileTest :
    FunSpec({

        beforeEach { ProfileRegistry.clear() }
        afterEach { ProfileRegistry.clear() }

        // ── Test 1: Whitelist — exactly 3 stereotypes (V1.1.2: +Scheduled) ────────

        test("springProfile has exactly 3 stereotypes") {
            val names = springProfile.stereotypes.map { it.name }.toSet()
            names shouldContainExactlyInAnyOrder setOf("RestController", "SpringData", "Scheduled")
            springProfile.stereotypes.size shouldBe 3
        }

        // ── Test 2: springProfile extends javaEeProfile by namespace ─────────────

        test("springProfile extends javaEeProfile by namespace") {
            springProfile.extendsProfiles shouldBe listOf("dev.kuml.profiles.javaee")
        }

        // ── Test 3: RestController specializes Controller ─────────────────────────

        test("RestController specializes Controller") {
            val rc = springProfile.stereotype("RestController")
            rc shouldNotBe null
            rc!!.specializes shouldBe "Controller"
        }

        // ── Test 4: SpringData specializes Repository ─────────────────────────────

        test("SpringData specializes Repository") {
            val sd = springProfile.stereotype("SpringData")
            sd shouldNotBe null
            sd!!.specializes shouldBe "Repository"
        }

        // ── Test 5: ServiceLoader discovery (both profiles on classpath) ──────────

        test("springProfile is discovered via ProfileRegistry.loadFromClasspath") {
            ProfileRegistry.loadFromClasspath()
            val found = ProfileRegistry.get("dev.kuml.profiles.spring")
            found shouldNotBe null
            found!!.name shouldBe "Spring"
            found.stereotype("RestController") shouldNotBe null
            found.stereotype("SpringData") shouldNotBe null
        }

        // ── Test 6: springProfile alone does not contain JavaEE stereotypes ───────

        test("springProfile does not directly contain Entity or Controller stereotypes") {
            // Entity and Controller live in JavaEE, not Spring's own stereotype list
            springProfile.stereotype("Entity") shouldBe null
            springProfile.stereotype("Controller") shouldBe null
        }

        // ── Test 7: RestController has produces and consumes properties ───────────

        test("RestController has produces and consumes properties with json defaults") {
            val rc = springProfile.stereotype("RestController")
            rc shouldNotBe null
            rc!!.properties.size shouldBe 2

            val produces = rc.properties.first { it.name == "produces" }
            produces.default shouldBe "application/json"

            val consumes = rc.properties.first { it.name == "consumes" }
            consumes.default shouldBe "application/json"
        }

        // ── Test 8: validateClosure passes when both profiles are registered ──────

        test("validateClosure passes when both JavaEE and Spring are registered") {
            ProfileRegistry.register(javaEeProfile)
            ProfileRegistry.register(springProfile)
            // Should not throw — Controller is in JavaEE, accessible via extends-closure
            ProfileRegistry.validateClosure()
        }

        // ── Test 9: Class-level Spring stereotypes target UmlMetaclass.Class ───────

        test("RestController and SpringData target UmlMetaclass.Class") {
            for (name in listOf("RestController", "SpringData")) {
                val s = springProfile.stereotype(name)
                s shouldNotBe null
                s!!.targetMetaclass shouldBe UmlMetaclass.Class
            }
        }

        // ── Test 10 (V1.1.2): Scheduled targets UmlMetaclass.Operation ───────────

        test("Scheduled targets UmlMetaclass.Operation") {
            val scheduled = springProfile.stereotype("Scheduled")
            scheduled shouldNotBe null
            scheduled!!.targetMetaclass shouldBe UmlMetaclass.Operation
        }

        // ── Test 11 (V1.1.2): Scheduled has cron, fixedRate, initialDelay ─────────

        test("Scheduled has cron (default ''), fixedRate (default 0), initialDelay (default 0)") {
            val scheduled = springProfile.stereotype("Scheduled")
            scheduled shouldNotBe null
            scheduled!!.properties.size shouldBe 3

            val cron = scheduled.properties.first { it.name == "cron" }
            cron.required shouldBe false
            cron.default shouldBe ""

            val fixedRate = scheduled.properties.first { it.name == "fixedRate" }
            fixedRate.required shouldBe false
            fixedRate.default shouldBe 0L

            val initialDelay = scheduled.properties.first { it.name == "initialDelay" }
            initialDelay.required shouldBe false
            initialDelay.default shouldBe 0L
        }

        // ── Test 12 (V1.1.2): Scheduled DSL stores appliedStereotype ─────────────

        test("Scheduled applied via DSL stores entry in operation appliedStereotypes") {
            val diagram =
                classDiagram("Scheduled Test") {
                    applyProfile(springProfile)
                    classOf("ReportScheduler") {
                        operation("generateDaily") {
                            stereotype("Scheduled") {
                                "cron" to "0 0 * * *"
                            }
                        }
                    }
                }
            val cls = diagram.elements.filterIsInstance<UmlClass>().first()
            val op = cls.operations.first()
            op.appliedStereotypes.size shouldBe 1
            op.appliedStereotypes.first().stereotypeName shouldBe "Scheduled"
        }
    })
