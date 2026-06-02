package dev.kuml.llm.bench

import dev.kuml.llm.anthropic.AnthropicBackend
import dev.kuml.llm.core.LlmMockBackend

/**
 * Entry point for the kUML LLM Benchmark runner.
 *
 * Usage:
 *   ./gradlew :kuml-llm:kuml-llm-bench:run --args="--mock"      # dry run with mock backend
 *   ANTHROPIC_API_KEY=sk-... ./gradlew :kuml-llm:kuml-llm-bench:run  # live run
 *
 * Output: benchmark-report.md in the working directory.
 */
public fun main(args: Array<String>) {
    val useMock = "--mock" in args
    val backend =
        if (useMock) {
            LlmMockBackend()
        } else {
            val apiKey =
                System.getenv("ANTHROPIC_API_KEY")
                    ?: error("ANTHROPIC_API_KEY environment variable not set. Use --mock for a dry run.")
            AnthropicBackend(apiKey = apiKey)
        }

    println("kUML LLM Benchmark")
    println("Backend: ${backend.id}")
    println("Tasks: ${BENCHMARK_TASKS.size}")
    println()

    val report =
        BenchmarkRunner.run(
            tasks = BENCHMARK_TASKS,
            backend = backend,
            onProgress = { result ->
                val status = if (result.valid) "✅" else "❌"
                println("$status ${result.taskId} (${result.durationMs}ms)")
                if (!result.valid) println("   └─ ${result.validationMessage}")
            },
        )

    println()
    println("─".repeat(60))
    println("Success rate: ${"%.1f".format(report.successRate * 100)}% (${report.successfulTasks}/${report.totalTasks})")
    println("─".repeat(60))

    val reportFile = java.io.File("benchmark-report.md")
    reportFile.writeText(report.toMarkdown())
    println("Report written to: ${reportFile.absolutePath}")
}
