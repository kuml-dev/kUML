package dev.kuml.ai.bench

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLModel
import dev.kuml.ai.KumlAiExecutor
import dev.kuml.ai.provider.ProviderRegistry

/**
 * Executes a list of [BenchTask]s against a [KumlAiExecutor] and produces a [BenchReport].
 *
 * Token counts are set to 0 because the simple non-agent executor path does not surface
 * per-call usage metadata from Koog's response types. Latency is measured wall-clock.
 */
public object AiBench {
    /**
     * Sentinel exception thrown when the very first task fails with a connection-refusal
     * or unreachable-host error, indicating that the provider itself is not reachable
     * (as opposed to a task-content failure). The CLI maps this to exit code `PROVIDER_UNREACHABLE`.
     */
    public class ProviderUnreachableException(
        public val providerId: String,
        cause: Throwable,
    ) : Exception("Provider '$providerId' is unreachable: ${cause.message}", cause)

    /**
     * Run all [tasks] sequentially against [executor] using [model], collecting results.
     *
     * @param tasks List of tasks to run (typically from [BenchTaskSuite]).
     * @param executor Configured [KumlAiExecutor] pointing at the target provider.
     * @param provider Provider id string for the report (e.g. "ollama").
     * @param model Koog [LLModel] to dispatch to.
     * @throws ProviderUnreachableException if the very first task fails with a connection error.
     */
    public suspend fun run(
        tasks: List<BenchTask>,
        executor: KumlAiExecutor,
        provider: String,
        model: LLModel,
    ): BenchReport {
        val results = mutableListOf<BenchTaskResult>()
        var firstTask = true

        for (task in tasks) {
            val startMs = System.currentTimeMillis()
            try {
                val koogPrompt =
                    prompt(task.id) {
                        if (task.systemPrompt.isNotBlank()) system(task.systemPrompt)
                        user(task.userPrompt)
                    }

                val response = executor.execute(koogPrompt, model)
                val latencyMs = System.currentTimeMillis() - startMs

                // Koog 1.0.0: execute() returns Message.Assistant directly.
                // textContent() collapses all text parts into a single String.
                val actual = response.textContent()

                val pass =
                    task.expectedSubstrings.all { expected ->
                        actual.contains(expected, ignoreCase = false)
                    }

                results +=
                    BenchTaskResult(
                        task = task,
                        actual = actual,
                        pass = pass,
                        latencyMs = latencyMs,
                        inputTokens = 0L,
                        outputTokens = 0L,
                    )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val latencyMs = System.currentTimeMillis() - startMs
                val isConnectionError = isConnectionError(e)

                // First task connection failure → provider unreachable
                if (firstTask && isConnectionError) {
                    throw ProviderUnreachableException(provider, e)
                }

                results +=
                    BenchTaskResult(
                        task = task,
                        actual = "",
                        pass = false,
                        latencyMs = latencyMs,
                        inputTokens = 0L,
                        outputTokens = 0L,
                        error = e.message ?: e::class.simpleName ?: "unknown error",
                    )
            }
            firstTask = false
        }

        return BenchReport(provider = provider, model = model.id, results = results)
    }

    private fun isConnectionError(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: ""
        val cause = e.cause?.message?.lowercase() ?: ""
        val type = e::class.simpleName?.lowercase() ?: ""
        return listOf(msg, cause, type).any { s ->
            s.contains("connection refused") ||
                s.contains("connect") ||
                s.contains("unreachable") ||
                s.contains("socketexception") ||
                s.contains("connectexception") ||
                s.contains("network") ||
                s.contains("nodename nor servname") ||
                s.contains("name or service not known") ||
                s.contains("failed to connect")
        }
    }
}

/**
 * Resolve the Koog [LLModel] for a given [providerId] and [modelId].
 * Returns null if the pair cannot be resolved from the built-in registry.
 */
public fun resolveModel(
    providerId: String,
    modelId: String,
): LLModel? = ProviderRegistry.builtIns().resolveModel(providerId, modelId)
