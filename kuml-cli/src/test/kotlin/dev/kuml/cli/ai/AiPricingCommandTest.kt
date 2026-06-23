package dev.kuml.cli.ai

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.KumlCli
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Tests for `kuml ai pricing` command.
 * AP-6.5: verifies pricing table display using the bundled fallback (--no-fetch).
 */
class AiPricingCommandTest :
    FunSpec({

        // ── Test 1: exit 0 with bundled fallback ─────────────────────────────

        test("ai pricing --no-fetch exits 0") {
            val result = KumlCli().test("ai pricing --no-fetch")
            result.statusCode shouldBe 0
        }

        // ── Test 2: output contains $/MTok header ────────────────────────────

        test("ai pricing --no-fetch output contains MTok header") {
            val result = KumlCli().test("ai pricing --no-fetch")
            result.output shouldContain "MTok"
        }

        // ── Test 3: output contains known providers ──────────────────────────

        test("ai pricing --no-fetch shows openai and anthropic entries") {
            val result = KumlCli().test("ai pricing --no-fetch")
            result.output shouldContain "openai"
            result.output shouldContain "anthropic"
        }

        // ── Test 4: output contains gpt-4o ───────────────────────────────────

        test("ai pricing --no-fetch shows gpt-4o") {
            val result = KumlCli().test("ai pricing --no-fetch")
            result.output shouldContain "gpt-4o"
        }

        // ── Test 5: ollama shown as free ─────────────────────────────────────

        test("ai pricing --no-fetch shows free for ollama models") {
            val result = KumlCli().test("ai pricing --no-fetch")
            result.output shouldContain "ollama"
            result.output shouldContain "free"
        }

        // ── Test 6: --format json exits 0 and produces valid JSON ────────────

        test("ai pricing --no-fetch --format json exits 0 and output contains schemaVersion") {
            val result = KumlCli().test("ai pricing --no-fetch --format json")
            result.statusCode shouldBe 0
            result.output shouldContain "schemaVersion"
            result.output shouldContain "entries"
            result.output shouldContain "gpt-4o"
        }

        // ── Test 7: fallback label in text output ────────────────────────────

        test("ai pricing --no-fetch text output mentions bundled fallback") {
            val result = KumlCli().test("ai pricing --no-fetch")
            result.output shouldContain "bundled fallback"
        }
    })
