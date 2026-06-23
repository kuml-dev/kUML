package dev.kuml.cli.ai

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import dev.kuml.ai.KumlAiExecutor
import dev.kuml.ai.bench.AiBench
import dev.kuml.ai.bench.BenchReport
import dev.kuml.ai.bench.BenchTaskResult
import dev.kuml.ai.bench.BenchTaskSuite
import dev.kuml.ai.bench.resolveModel
import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.settings.KumlAiSettingsStore
import dev.kuml.ai.vault.ApiKeyVault
import dev.kuml.cli.ExitCodes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * `kuml ai bench` — run a benchmark task suite against an LLM provider.
 *
 * Exercises the provider through [KumlAiExecutor] using the configured [BenchTaskSuite].
 * Results are displayed as a pass/fail table with latency, or as JSON.
 *
 * Options:
 *   --provider <id>    Provider to bench (default: ollama).
 *   --model <id>       Model to use (default: from settings or "llama3.2" for ollama).
 *   --tasks <N>        Run only the first N tasks from the suite.
 *   --format text|json Output format.
 *   --output <path>    Write the report to a file (optional, in addition to stdout).
 *
 * Exit codes:
 *   0  — all tasks passed.
 *   [ExitCodes.BENCH_FAILED] (60) — at least one task failed.
 *   [ExitCodes.PROVIDER_UNREACHABLE] (61) — provider could not be reached.
 */
internal class AiBenchCommand(
    /** Test-only: inject executor instead of building from settings. */
    private val executorFactory: ((KumlAiSettings) -> KumlAiExecutor)? = null,
) : CliktCommand(name = "bench") {
    private val provider by option("--provider", help = "Provider id to benchmark (e.g. ollama, openai, anthropic)")
        .default("ollama")

    private val model by option("--model", help = "Model id to use (defaults to the provider's configured default)")

    private val tasks by option("--tasks", help = "Run only the first N tasks from the suite")
        .int()

    private val format by option("--format", help = "Output format: text or json")
        .choice("text", "json")
        .default("text")

    private val outputPath by option("--output", help = "Write report to this file path (optional)")

    override fun help(context: Context): String = "Run the kUML benchmark task suite against a local or cloud LLM provider."

    override fun run() {
        val settings = buildSettings()
        val resolvedModel = resolveModelId(settings)
        val koogModel =
            resolveModel(provider, resolvedModel)
                ?: run {
                    System.err.println(
                        "Cannot resolve model '$resolvedModel' for provider '$provider'. " +
                            "Check that the provider and model ids are correct.",
                    )
                    throw ProgramResult(ExitCodes.PROVIDER_UNREACHABLE)
                }

        val executor = buildExecutor(settings)

        val suite =
            tasks?.let { BenchTaskSuite.take(it) } ?: BenchTaskSuite.all

        echo("Running ${suite.size} benchmark tasks — provider=$provider model=$resolvedModel\n")

        val report =
            runBlocking {
                try {
                    AiBench.run(suite, executor, provider, koogModel)
                } catch (e: AiBench.ProviderUnreachableException) {
                    System.err.println("Provider '$provider' is not reachable: ${e.message}")
                    System.err.println(
                        "For Ollama, ensure it is running: ollama serve",
                    )
                    throw ProgramResult(ExitCodes.PROVIDER_UNREACHABLE)
                }
            }

        // Output the report
        val rendered =
            when (format) {
                "json" -> renderJson(report)
                else -> renderText(report)
            }

        echo(rendered)

        outputPath?.let { path ->
            val file = java.io.File(path)
            file.writeText(rendered)
            echo("\nReport written to $path")
        }

        if (!report.allPassed) {
            throw ProgramResult(ExitCodes.BENCH_FAILED)
        }
    }

    private fun buildSettings(): KumlAiSettings {
        val base = KumlAiSettingsStore().load()
        // Override default provider for this bench run
        val enabledProviders = (base.enabledProviders + provider).toSet()
        return base.copy(
            defaultProvider = provider,
            enabledProviders = enabledProviders,
        )
    }

    private fun resolveModelId(settings: KumlAiSettings): String =
        model
            ?: settings.defaultModels[provider]
            ?: when (provider) {
                "ollama" -> "llama3.2"
                "openai" -> "gpt-4o"
                "anthropic" -> "claude-sonnet-4-5"
                "google" -> "gemini-1.5-pro"
                else -> "llama3.2"
            }

    private fun buildExecutor(settings: KumlAiSettings): KumlAiExecutor {
        executorFactory?.let { return it(settings) }
        val vault = ApiKeyVault.detect()
        return KumlAiExecutor.fromSettings(settings, vault)
    }

    private fun renderText(report: BenchReport): String {
        val sb = StringBuilder()
        sb.appendLine("Benchmark Results — ${report.provider} / ${report.model}")
        sb.appendLine("${report.passed}/${report.total} tasks passed\n")

        val idWidth = report.results.maxOf { it.task.id.length }.coerceAtLeast(12)
        val statusWidth = 6
        val latWidth = 10

        val header =
            "%-${idWidth}s  %-${statusWidth}s  %${latWidth}s  TOKENS IN/OUT  DETAIL".format(
                "TASK",
                "STATUS",
                "LATENCY ms",
            )
        sb.appendLine(header)
        sb.appendLine("-".repeat(header.length))

        for (r in report.results) {
            val status = if (r.pass) "PASS" else "FAIL"
            val detail = r.error ?: truncate(r.actual, 60)
            sb.appendLine(
                "%-${idWidth}s  %-${statusWidth}s  %${latWidth}d  %6d/%-6d  %s".format(
                    r.task.id,
                    status,
                    r.latencyMs,
                    r.inputTokens,
                    r.outputTokens,
                    detail,
                ),
            )
            if (!r.pass && r.error == null) {
                // Show expected vs actual on failure
                sb.appendLine("  Expected substrings: ${r.task.expectedSubstrings}")
                sb.appendLine("  Actual (truncated):  ${truncate(r.actual, 120)}")
            }
        }
        return sb.toString()
    }

    private fun renderJson(report: BenchReport): String {
        val json = Json { prettyPrint = true }
        return json.encodeToString(BenchReportJson.from(report))
    }

    private fun truncate(
        s: String,
        maxLen: Int,
    ): String = if (s.length <= maxLen) s else s.take(maxLen - 1) + "…"
}

/** JSON shape for `--format json` output. */
@Serializable
private data class BenchReportJson(
    val provider: String,
    val model: String,
    val passed: Int,
    val failed: Int,
    val total: Int,
    val allPassed: Boolean,
    val tasks: List<BenchTaskResultJson>,
) {
    companion object {
        fun from(r: BenchReport): BenchReportJson =
            BenchReportJson(
                provider = r.provider,
                model = r.model,
                passed = r.passed,
                failed = r.failed,
                total = r.total,
                allPassed = r.allPassed,
                tasks = r.results.map { BenchTaskResultJson.from(it) },
            )
    }
}

@Serializable
private data class BenchTaskResultJson(
    val id: String,
    val pass: Boolean,
    val latencyMs: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val actual: String,
    val error: String?,
) {
    companion object {
        fun from(r: BenchTaskResult): BenchTaskResultJson =
            BenchTaskResultJson(
                id = r.task.id,
                pass = r.pass,
                latencyMs = r.latencyMs,
                inputTokens = r.inputTokens,
                outputTokens = r.outputTokens,
                actual = r.actual,
                error = r.error,
            )
    }
}
