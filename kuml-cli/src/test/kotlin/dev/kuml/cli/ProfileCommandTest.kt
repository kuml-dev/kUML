package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import dev.kuml.profile.ProfileRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Integration tests for `kuml profile` subcommand tree.
 *
 * AP-6.3: Verifies list/show/validate subcommands with text and JSON output.
 */
class ProfileCommandTest :
    FunSpec({

        beforeEach {
            // Ensure registry starts clean so loadFromClasspath() is idempotent
            ProfileRegistry.clear()
        }

        afterEach {
            ProfileRegistry.clear()
        }

        // ── Test 1: kuml profile list shows all built-in profiles ────────────────

        test("profile list shows all built-in profiles") {
            val result = KumlCli().test("profile list")
            result.statusCode shouldBe 0
            result.output shouldContain "dev.kuml.profiles.soaml"
            result.output shouldContain "dev.kuml.profiles.javaee"
            result.output shouldContain "dev.kuml.profiles.spring"
            result.output shouldContain "dev.kuml.profiles.openapi"
            result.output shouldContain "dev.kuml.profiles.autosar"
            result.output shouldContain "dev.kuml.profiles.autosar.adaptive"
            // V3.4.7: kuml-gen-sql now depends on kuml-transform-uml-to-erm, which pulls the ERM
            // mapping profile onto kuml-cli's runtime classpath for the first time.
            result.output shouldContain "dev.kuml.profiles.erm"
        }

        // ── Test 2: kuml profile list --output json produces valid JSON ──────────

        test("profile list --output json produces valid JSON with all built-in profiles") {
            val result = KumlCli().test("profile list --output json")
            result.statusCode shouldBe 0

            // Must parse as valid JSON
            val json = Json.parseToJsonElement(result.output)
            val profiles =
                json.jsonObject["profiles"]?.jsonArray
                    ?: error("Expected 'profiles' array in JSON output")

            // V3.4.7: 6 pre-existing profiles + the ERM mapping profile (see Test 1).
            profiles.size shouldBe 7

            // Each entry must have a namespace field
            val namespaces = profiles.map { it.jsonObject["namespace"]?.toString()?.trim('"') ?: "" }
            namespaces shouldContain "dev.kuml.profiles.soaml"
            namespaces shouldContain "dev.kuml.profiles.javaee"
            namespaces shouldContain "dev.kuml.profiles.spring"
            namespaces shouldContain "dev.kuml.profiles.openapi"
            namespaces shouldContain "dev.kuml.profiles.autosar"
            namespaces shouldContain "dev.kuml.profiles.autosar.adaptive"
            namespaces shouldContain "dev.kuml.profiles.erm"
        }

        // ── Test 3: kuml profile show javaee shows 4 stereotypes ────────────────

        test("profile show dev.kuml.profiles.javaee shows 4 stereotypes") {
            val result = KumlCli().test("profile show dev.kuml.profiles.javaee")
            result.statusCode shouldBe 0
            result.output shouldContain "Entity"
            result.output shouldContain "Repository"
            result.output shouldContain "Service"
            result.output shouldContain "Controller"
        }

        // ── Test 4: kuml profile show unknown namespace → exit 1 ────────────────

        test("profile show unknown namespace exits 1 with clear error message") {
            val result = KumlCli().test("profile show dev.kuml.profiles.unknown")
            result.statusCode shouldBe 1
            result.output shouldContain "not found"
        }

        // ── Test 5: kuml profile validate spring → exit 0 ───────────────────────

        test("profile validate dev.kuml.profiles.spring exits 0 (specializes-closure is resolvable)") {
            val result = KumlCli().test("profile validate dev.kuml.profiles.spring")
            result.statusCode shouldBe 0
            result.output shouldContain "passed"
        }

        // ── Test 6: kuml profile show javaee --output json is valid JSON ─────────

        test("profile show javaee --output json produces valid JSON with stereotypes") {
            val result = KumlCli().test("profile show dev.kuml.profiles.javaee --output json")
            result.statusCode shouldBe 0

            val json = Json.parseToJsonElement(result.output)
            val ns = json.jsonObject["namespace"]?.toString()?.trim('"')
            ns shouldBe "dev.kuml.profiles.javaee"

            val stereotypes =
                json.jsonObject["stereotypes"]?.jsonArray
                    ?: error("Expected 'stereotypes' array in JSON output")
            stereotypes.size shouldBe 5 // V1.1.2: +PersistenceContext
        }
    })
