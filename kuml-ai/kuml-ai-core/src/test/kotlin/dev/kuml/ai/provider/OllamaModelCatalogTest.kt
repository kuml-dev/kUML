package dev.kuml.ai.provider

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * [BuiltInProviders.fetchOllamaModelIds] talks to a real, fixed local endpoint
 * (`http://localhost:11434`, no override surface — see its KDoc's SSRF note), so most of its
 * behaviour cannot be tested deterministically without either an actual Ollama process or a
 * stub HTTP server standing in for one. This suite keeps two kinds of test apart:
 *
 *  - Deterministic, always-run tests below that do not depend on whether Ollama happens to be
 *    installed/running on the machine executing the build.
 *  - A `@Tags("live")` end-to-end test (same convention as
 *    [dev.kuml.ai.integration.EndToEndOllamaLiveTest]) that requires a real local Ollama and is
 *    excluded by default (`excludeTags("live")` in build.gradle.kts unless
 *    `-Dkuml.ai.test.live=true` is passed).
 */
class OllamaModelCatalogTest :
    FunSpec({

        test("timeoutMs = 0 always yields a Failure — deterministic regardless of whether Ollama is running") {
            runTest {
                // withTimeoutOrNull(0) is documented kotlinx.coroutines behaviour: it throws/
                // returns null immediately without running the block at all, so this is
                // deterministic on every machine — unlike a real network call, whose outcome
                // (Success if Ollama happens to be running locally, Failure via connection
                // refused otherwise) is environment-dependent.
                val result = BuiltInProviders.fetchOllamaModelIds(timeoutMs = 0)
                result.shouldBeInstanceOf<OllamaModelListResult.Failure>()
                result.reason.shouldNotBeEmptyReason()
            }
        }

        test("never throws — always completes with a well-formed sealed result within the default timeout") {
            runTest {
                // Deliberately does NOT assert Success vs Failure — whether a local Ollama
                // happens to be running on the machine executing this test is environment-
                // dependent (see class KDoc). What IS guaranteed by fetchOllamaModelIds's
                // contract (no silent fallback that pretends the call succeeded, no crash
                // either) is that it always returns one of the two sealed cases, never throws.
                val result = BuiltInProviders.fetchOllamaModelIds(timeoutMs = 5_000)
                when (result) {
                    is OllamaModelListResult.Success -> Unit
                    is OllamaModelListResult.Failure -> result.reason.shouldNotBeEmptyReason()
                }
            }
        }

        test("OllamaModelListResult.Success carries the model ids unchanged") {
            val result = OllamaModelListResult.Success(modelIds = listOf("llama3.2", "qwen3-coder:30b"))
            result.modelIds.shouldNotBeEmpty()
            result.modelIds shouldBe listOf("llama3.2", "qwen3-coder:30b")
        }
    })

private fun String.shouldNotBeEmptyReason() {
    require(this.isNotBlank()) { "expected a non-blank failure reason" }
}

@Tags("live")
class OllamaModelCatalogLiveTest :
    FunSpec({

        test("against a real local Ollama, fetchOllamaModelIds returns the actually pulled models") {
            val liveEnabled = System.getProperty("kuml.ai.test.live") == "true"
            if (!liveEnabled) {
                println("Skipping live Ollama model-catalog test — set -Dkuml.ai.test.live=true to enable")
                return@test
            }
            runTest {
                val result = BuiltInProviders.fetchOllamaModelIds()
                result.shouldBeInstanceOf<OllamaModelListResult.Success>()
                result.modelIds.shouldNotBeEmpty()
            }
        }
    })
