package dev.kuml.profile.autosar

import dev.kuml.profile.ProfileRegistry
import dev.kuml.profile.UmlMetaclass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * AP-5b.2 profile tests for the AUTOSAR profile.
 *
 * Covers:
 * 1. Whitelist — exactly 3 stereotypes (SoftwareComponent, ComInterface, AutosarPort)
 * 2. SoftwareComponent has Enum property kind with default Application  ← Enum-Property-Test
 * 3. AutosarPort has Enum property direction with default Provided       ← Enum-Property-Test
 * 4. Each stereotype targets correct UmlMetaclass
 * 5. ServiceLoader discovery
 * 6. Profile metadata
 * 7. ComInterface has version and isService properties
 */
class AutosarProfileTest :
    FunSpec({

        beforeEach { ProfileRegistry.clear() }
        afterEach { ProfileRegistry.clear() }

        // ── Test 1: Whitelist — exactly 3 stereotypes ────────────────────────────

        test("autosarProfile has exactly 3 stereotypes (SoftwareComponent, ComInterface, AutosarPort)") {
            val names = autosarProfile.stereotypes.map { it.name }.toSet()
            names shouldContainExactlyInAnyOrder setOf("SoftwareComponent", "ComInterface", "AutosarPort")
            autosarProfile.stereotypes.size shouldBe 3
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
    })
