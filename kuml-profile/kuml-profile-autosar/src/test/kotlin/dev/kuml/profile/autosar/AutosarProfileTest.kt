package dev.kuml.profile.autosar

import dev.kuml.core.dsl.classDiagram
import dev.kuml.profile.ProfileRegistry
import dev.kuml.profile.UmlMetaclass
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
 * AP-5b.2 profile tests for the AUTOSAR profile (V1.1.2 updated).
 *
 * Covers:
 * 1. Whitelist — exactly 5 stereotypes (V1.1.2: +Runnable, +BehaviorSpec)
 * 2. SoftwareComponent has Enum property kind with default Application  ← Enum-Property-Test
 * 3. AutosarPort has Enum property direction with default Provided       ← Enum-Property-Test
 * 4. Each stereotype targets correct UmlMetaclass
 * 5. ServiceLoader discovery
 * 6. Profile metadata
 * 7. ComInterface has version and isService properties
 * 8. V1.1.2: Runnable stereotype tests
 * 9. V1.1.2: BehaviorSpec stereotype tests
 */
class AutosarProfileTest :
    FunSpec({

        beforeEach { ProfileRegistry.clear() }
        afterEach { ProfileRegistry.clear() }

        // ── Test 1: Whitelist — exactly 5 stereotypes (V1.1.2: +Runnable, +BehaviorSpec) ─

        test("autosarProfile has exactly 5 stereotypes") {
            val names = autosarProfile.stereotypes.map { it.name }.toSet()
            names shouldContainExactlyInAnyOrder
                setOf("SoftwareComponent", "ComInterface", "AutosarPort", "Runnable", "BehaviorSpec")
            autosarProfile.stereotypes.size shouldBe 5
        }

        // ── Test 2: SoftwareComponent has Enum property kind with default Application ──

        test("SoftwareComponent has Enum property kind with default Application") {
            val swc = autosarProfile.stereotype("SoftwareComponent")
            swc shouldNotBe null
            swc!!.properties.size shouldBe 2

            val kind = swc.properties.first { it.name == "kind" }
            kind.required shouldBe false
            kind.default shouldBe AutosarSwcKind.Application
            kind.type shouldBe AutosarSwcKind::class
        }

        // ── Test 3: AutosarPort has Enum property direction with default Provided ─

        test("AutosarPort has Enum property direction with default Provided") {
            val port = autosarProfile.stereotype("AutosarPort")
            port shouldNotBe null
            port!!.properties.size shouldBe 1

            val direction = port.properties.first { it.name == "direction" }
            direction.required shouldBe false
            direction.default shouldBe AutosarPortDirection.Provided
            direction.type shouldBe AutosarPortDirection::class
        }

        // ── Test 4: Each stereotype targets correct UmlMetaclass ─────────────────

        test("each stereotype targets correct UmlMetaclass") {
            val swc = autosarProfile.stereotype("SoftwareComponent")
            swc shouldNotBe null
            swc!!.targetMetaclass shouldBe UmlMetaclass.Component

            val comIf = autosarProfile.stereotype("ComInterface")
            comIf shouldNotBe null
            comIf!!.targetMetaclass shouldBe UmlMetaclass.Interface

            val autosarPort = autosarProfile.stereotype("AutosarPort")
            autosarPort shouldNotBe null
            autosarPort!!.targetMetaclass shouldBe UmlMetaclass.Port
        }

        // ── Test 5: ServiceLoader discovery ──────────────────────────────────────

        test("autosarProfile is discovered via ProfileRegistry.loadFromClasspath") {
            ProfileRegistry.loadFromClasspath()
            val found = ProfileRegistry.get("dev.kuml.profiles.autosar")
            found shouldNotBe null
            found!!.name shouldBe "AUTOSAR"
            found.stereotype("SoftwareComponent") shouldNotBe null
            found.stereotype("ComInterface") shouldNotBe null
            found.stereotype("AutosarPort") shouldNotBe null
            found.stereotype("Runnable") shouldNotBe null
            found.stereotype("BehaviorSpec") shouldNotBe null
        }

        // ── Test 6: Profile metadata ──────────────────────────────────────────────

        test("autosarProfile has correct namespace, version and no parent profiles") {
            autosarProfile.namespace shouldBe "dev.kuml.profiles.autosar"
            autosarProfile.version shouldBe "1.0.0"
            autosarProfile.extendsProfiles shouldBe emptyList()
        }

        // ── Test 7: ComInterface has version and isService properties ─────────────

        test("ComInterface has version (default 1.0) and isService (default false)") {
            val comIf = autosarProfile.stereotype("ComInterface")
            comIf shouldNotBe null
            comIf!!.properties.size shouldBe 2

            val version = comIf.properties.first { it.name == "version" }
            version.required shouldBe false
            version.default shouldBe "1.0"

            val isService = comIf.properties.first { it.name == "isService" }
            isService.required shouldBe false
            isService.default shouldBe false
        }

        // ── Test 8 (V1.1.2): Runnable targets UmlMetaclass.Operation ─────────────

        test("Runnable targets UmlMetaclass.Operation") {
            val runnable = autosarProfile.stereotype("Runnable")
            runnable shouldNotBe null
            runnable!!.targetMetaclass shouldBe UmlMetaclass.Operation
        }

        // ── Test 9 (V1.1.2): Runnable has kind and periodMs properties ───────────

        test("Runnable has kind (default EventTriggered) and periodMs (default 0)") {
            val runnable = autosarProfile.stereotype("Runnable")
            runnable shouldNotBe null
            runnable!!.properties.size shouldBe 2

            val kind = runnable.properties.first { it.name == "kind" }
            kind.required shouldBe false
            kind.default shouldBe AutosarBehaviorKind.EventTriggered
            kind.type shouldBe AutosarBehaviorKind::class

            val periodMs = runnable.properties.first { it.name == "periodMs" }
            periodMs.required shouldBe false
            periodMs.default shouldBe 0L
        }

        // ── Test 10 (V1.1.2): BehaviorSpec targets UmlMetaclass.StateMachine ──────

        test("BehaviorSpec targets UmlMetaclass.StateMachine") {
            val bs = autosarProfile.stereotype("BehaviorSpec")
            bs shouldNotBe null
            bs!!.targetMetaclass shouldBe UmlMetaclass.StateMachine
        }

        // ── Test 11 (V1.1.2): BehaviorSpec has specName property ─────────────────

        test("BehaviorSpec has specName property with default empty string") {
            val bs = autosarProfile.stereotype("BehaviorSpec")
            bs shouldNotBe null
            bs!!.properties.size shouldBe 1

            val specName = bs.properties.first { it.name == "specName" }
            specName.required shouldBe false
            specName.default shouldBe ""
        }

        // ── Test 12 (V1.1.2): Runnable DSL stores appliedStereotype ──────────────

        test("Runnable applied via DSL stores entry in operation appliedStereotypes") {
            val diagram =
                classDiagram("Runnable Test") {
                    applyProfile(autosarProfile)
                    classOf("BrakeController") {
                        operation("onCycle") {
                            stereotype("Runnable") {
                                "kind" to AutosarBehaviorKind.Periodic
                                "periodMs" to 10L
                            }
                        }
                    }
                }
            val cls = diagram.elements.filterIsInstance<UmlClass>().first()
            val op = cls.operations.first()
            op.appliedStereotypes.size shouldBe 1
            op.appliedStereotypes.first().stereotypeName shouldBe "Runnable"
        }
    })
