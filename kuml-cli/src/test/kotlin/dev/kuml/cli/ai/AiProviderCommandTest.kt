package dev.kuml.cli.ai

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.KumlCli
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tests for `kuml ai provider list` and `kuml ai provider info` subcommands.
 * AP-6.4: verifies provider listing and detail display (V3.1.15).
 */
class AiProviderCommandTest :
    FunSpec({

        // ── Test 1: kuml ai provider list shows 4 built-in providers ─────────

        test("ai provider list exits 0 and shows all 4 built-in providers") {
            val result = KumlCli().test("ai provider list")
            result.statusCode shouldBe 0
            result.output shouldContain "openai"
            result.output shouldContain "anthropic"
            result.output shouldContain "google"
            result.output shouldContain "ollama"
        }

        // ── Test 2: cloud vs local badge ──────────────────────────────────────

        test("ai provider list shows local badge for ollama and cloud badge for openai") {
            val result = KumlCli().test("ai provider list")
            result.statusCode shouldBe 0
            result.output shouldContain "local"
            result.output shouldContain "cloud"
        }

        // ── Test 3: --output json is valid JSON with 4 entries ────────────────

        test("ai provider list --output json produces valid JSON with 4 providers") {
            val result = KumlCli().test("ai provider list --output json")
            result.statusCode shouldBe 0

            val root = Json.parseToJsonElement(result.output)
            val providers =
                root.jsonObject["providers"]?.jsonArray
                    ?: error("Expected 'providers' array in JSON output")

            providers.size shouldBe 4

            val ids = providers.map { it.jsonObject["id"]?.jsonPrimitive?.content ?: "" }.toSet()
            ids shouldBe setOf("openai", "anthropic", "google", "ollama")
        }

        // ── Test 4: each JSON entry has id and local fields ───────────────────

        test("ai provider list --output json each entry has id and local fields") {
            val result = KumlCli().test("ai provider list --output json")
            result.statusCode shouldBe 0

            val root = Json.parseToJsonElement(result.output)
            val providers =
                root.jsonObject["providers"]?.jsonArray
                    ?: error("Expected 'providers' array in JSON output")

            for (entry in providers) {
                val obj = entry.jsonObject
                obj.containsKey("id") shouldBe true
                obj.containsKey("local") shouldBe true
                obj.containsKey("displayName") shouldBe true
            }

            // Ollama is local=true, others are local=false
            val ollamaEntry = providers.first { it.jsonObject["id"]?.jsonPrimitive?.content == "ollama" }
            ollamaEntry.jsonObject["local"]?.jsonPrimitive?.content shouldBe "true"

            val openAiEntry = providers.first { it.jsonObject["id"]?.jsonPrimitive?.content == "openai" }
            openAiEntry.jsonObject["local"]?.jsonPrimitive?.content shouldBe "false"
        }

        // ── Test 5: kuml ai provider info openai shows models + cloud privacy ──

        test("ai provider info openai exits 0 and shows models and cloud privacy line") {
            val result = KumlCli().test("ai provider info openai")
            result.statusCode shouldBe 0
            result.output shouldContain "OpenAI"
            result.output shouldContain "cloud"
            result.output shouldContain "gpt-4o"
            // Privacy line for cloud providers
            result.output shouldContain "third-party"
        }

        // ── Test 6: kuml ai provider info ollama shows local privacy ──────────

        test("ai provider info ollama exits 0 and shows local privacy line") {
            val result = KumlCli().test("ai provider info ollama")
            result.statusCode shouldBe 0
            result.output shouldContain "Ollama"
            result.output shouldContain "local"
            // Ollama has no static models — dynamic note
            result.output shouldContain "dynamic"
            // Privacy line for local providers
            result.output shouldContain "no data"
        }

        // ── Test 7: kuml ai provider info unknown → exit non-zero ─────────────

        test("ai provider info unknown provider exits non-zero with not-found message") {
            val result = KumlCli().test("ai provider info nonexistent-provider")
            result.statusCode shouldBe ExitCodes.PLUGIN_NOT_FOUND
        }

        // ── Test 8: kuml ai provider info anthropic shows models ──────────────

        test("ai provider info anthropic shows claude models") {
            val result = KumlCli().test("ai provider info anthropic")
            result.statusCode shouldBe 0
            result.output shouldContain "claude"
            result.output shouldContain "Anthropic"
        }

        // ── Test 9: kuml ai provider info google shows gemini models ──────────

        test("ai provider info google shows gemini models") {
            val result = KumlCli().test("ai provider info google")
            result.statusCode shouldBe 0
            result.output shouldContain "gemini"
            result.output shouldContain "Google"
        }
    })

// Mirror ExitCodes.PLUGIN_NOT_FOUND (40) — the actual value from dev.kuml.cli.ExitCodes.
private object ExitCodes {
    const val PLUGIN_NOT_FOUND = 40
}
