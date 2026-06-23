package dev.kuml.ai.bench

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AssistantMessageBuilder
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import dev.kuml.ai.KumlAiExecutor
import dev.kuml.ai.privacy.PrivacyEnforcer
import dev.kuml.ai.provider.ProviderRegistry
import dev.kuml.ai.settings.KumlAiSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

private fun assistantMessage(text: String): Message.Assistant = AssistantMessageBuilder().content(text).build()

/**
 * Fake executor that returns canned responses based on prompt id.
 * [responseMap] maps prompt.id to the text to return.
 * If the prompt id is not found, returns the [defaultResponse].
 * If [throwOnFirst] is true, throws an IOException on the first call (simulates connection error).
 */
private class FakePromptExecutor(
    private val responseMap: Map<String, String> = emptyMap(),
    private val defaultResponse: String = "ok",
    private val throwOnFirst: Boolean = false,
) : PromptExecutor() {
    private var callCount = 0

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<Message.Response> {
        callCount++
        if (throwOnFirst && callCount == 1) {
            throw java.io.IOException("Connection refused")
        }
        val text = responseMap[prompt.id] ?: defaultResponse
        return listOf(assistantMessage(text))
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flowOf(StreamFrame.End("stop"))

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel,
    ): ModerationResult = ModerationResult(isHarmful = false, categories = emptyMap())

    override fun close(): Unit = Unit
}

private fun fakeExecutor(
    responseMap: Map<String, String> = emptyMap(),
    defaultResponse: String = "ok",
    throwOnFirst: Boolean = false,
): KumlAiExecutor =
    KumlAiExecutor.forTest(
        delegate = FakePromptExecutor(responseMap, defaultResponse, throwOnFirst),
        settings = KumlAiSettings(defaultProvider = "ollama", privacyMode = false),
        privacy = PrivacyEnforcer(privacyMode = false),
        registry = ProviderRegistry.builtIns(),
    )

/**
 * Tests for [AiBench] — pass/fail logic, latency, report aggregation,
 * provider-unreachable detection.
 */
class AiBenchTest :
    FunSpec({

        val ollamaModel = LLModel(LLMProvider.Ollama, "llama3.2")

        // ── Test 1: all tasks pass when response contains expected substrings ──

        test("all tasks pass when all expected substrings are present in response") {
            val tasks =
                listOf(
                    BenchTask("t1", "sys", "q1", listOf("class", "attribute")),
                    BenchTask("t2", "sys", "q2", listOf("Context")),
                )
            val executor =
                fakeExecutor(
                    responseMap =
                        mapOf(
                            "t1" to "A UML class diagram has class elements with attributes.",
                            "t2" to "C4 starts with Context then Container.",
                        ),
                )

            val report = AiBench.run(tasks, executor, "ollama", ollamaModel)

            report.allPassed.shouldBeTrue()
            report.passed shouldBe 2
            report.total shouldBe 2
        }

        // ── Test 2: task fails when expected substring is missing ─────────────

        test("task fails when a required substring is absent from the response") {
            val tasks =
                listOf(
                    BenchTask("fail-task", "", "What is UML?", listOf("xyz-not-there")),
                )
            val executor = fakeExecutor(defaultResponse = "UML is a modelling language.")

            val report = AiBench.run(tasks, executor, "ollama", ollamaModel)

            report.allPassed.shouldBeFalse()
            report.passed shouldBe 0
            report.failed shouldBe 1
            report.results[0].pass.shouldBeFalse()
            report.results[0].actual shouldContain "UML"
        }

        // ── Test 3: mixed pass/fail ────────────────────────────────────────────

        test("report correctly counts mixed pass and fail tasks") {
            val tasks =
                listOf(
                    BenchTask("pass1", "", "q", listOf("yes")),
                    BenchTask("fail1", "", "q", listOf("no-match")),
                    BenchTask("pass2", "", "q", listOf("yes")),
                )
            val executor = fakeExecutor(defaultResponse = "yes this is the answer")

            val report = AiBench.run(tasks, executor, "ollama", ollamaModel)

            report.passed shouldBe 2
            report.failed shouldBe 1
            report.allPassed.shouldBeFalse()
        }

        // ── Test 4: latency is non-negative ───────────────────────────────────

        test("all task results have non-negative latency") {
            val tasks = listOf(BenchTask("t1", "", "hello", listOf("ok")))
            val executor = fakeExecutor(defaultResponse = "ok response")

            val report = AiBench.run(tasks, executor, "ollama", ollamaModel)

            report.results.forEach { result ->
                result.latencyMs shouldBeGreaterThanOrEqualTo 0L
            }
        }

        // ── Test 5: error on first call → ProviderUnreachableException ────────

        test("connection error on first task produces ProviderUnreachableException") {
            val tasks = listOf(BenchTask("net-task", "", "q", listOf("ok")))
            val executor = fakeExecutor(throwOnFirst = true)

            shouldThrow<AiBench.ProviderUnreachableException> {
                AiBench.run(tasks, executor, "ollama", ollamaModel)
            }
        }

        // ── Test 6: error after first success is treated as task failure ───────

        test("error on second task does not produce ProviderUnreachableException") {
            val tasks =
                listOf(
                    BenchTask("first", "", "q1", listOf("ok")),
                    BenchTask("second", "", "q2", listOf("ok")),
                )
            // Executor succeeds first call, throws on second
            var call = 0
            val fakeDelegate =
                object : PromptExecutor() {
                    override suspend fun execute(
                        prompt: Prompt,
                        model: LLModel,
                        tools: List<ToolDescriptor>,
                    ): List<Message.Response> {
                        call++
                        if (call == 2) throw java.net.ConnectException("Connection refused")
                        return listOf(assistantMessage("ok response"))
                    }

                    override fun executeStreaming(
                        prompt: Prompt,
                        model: LLModel,
                        tools: List<ToolDescriptor>,
                    ): Flow<StreamFrame> = flowOf(StreamFrame.End("stop"))

                    override suspend fun moderate(
                        prompt: Prompt,
                        model: LLModel,
                    ): ModerationResult = ModerationResult(isHarmful = false, categories = emptyMap())

                    override fun close(): Unit = Unit
                }

            val executor =
                KumlAiExecutor.forTest(
                    delegate = fakeDelegate,
                    settings = KumlAiSettings(defaultProvider = "ollama", privacyMode = false),
                    privacy = PrivacyEnforcer(privacyMode = false),
                    registry = ProviderRegistry.builtIns(),
                )

            // Must not throw — second error is recorded as a task failure
            val report = AiBench.run(tasks, executor, "ollama", ollamaModel)
            report.results shouldHaveSize 2
            report.results[0].pass.shouldBeTrue()
            report.results[1].pass.shouldBeFalse()
            report.results[1].error.shouldNotBeNull()
        }

        // ── Test 7: BenchReport aggregates correctly ───────────────────────────

        test("BenchReport allPassed is false when any task failed") {
            val results =
                listOf(
                    BenchTaskResult(
                        BenchTask("a", "", "q", emptyList()),
                        "response",
                        pass = true,
                        latencyMs = 10L,
                        inputTokens = 0L,
                        outputTokens = 0L,
                    ),
                    BenchTaskResult(
                        BenchTask("b", "", "q", listOf("missing")),
                        "response",
                        pass = false,
                        latencyMs = 10L,
                        inputTokens = 0L,
                        outputTokens = 0L,
                    ),
                )
            val report = BenchReport("ollama", "llama3.2", results)
            report.allPassed.shouldBeFalse()
            report.passed shouldBe 1
            report.failed shouldBe 1
        }

        // ── Test 8: BenchTaskSuite.all is non-empty ────────────────────────────

        test("BenchTaskSuite default suite has at least 5 tasks") {
            BenchTaskSuite.all.size shouldBe 5
        }

        // ── Test 9: BenchTaskSuite.take caps to limit ─────────────────────────

        test("BenchTaskSuite.take(2) returns exactly 2 tasks") {
            BenchTaskSuite.take(2) shouldHaveSize 2
        }
    })
