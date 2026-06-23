package dev.kuml.ai.bench

/**
 * A single benchmark task: a prompt pair plus the expected substrings that must appear
 * in the model's response for the task to be considered passing.
 */
public data class BenchTask(
    /** Unique identifier for this task (e.g. "uml-class-basic"). */
    val id: String,
    /** System prompt sent to the model (may be empty). */
    val systemPrompt: String,
    /** User prompt describing the task. */
    val userPrompt: String,
    /**
     * All strings in this list must appear in the model's response (case-sensitive substring match)
     * for the task to be considered passing.
     */
    val expectedSubstrings: List<String>,
)

/** Result of running one [BenchTask]. */
public data class BenchTaskResult(
    val task: BenchTask,
    /** The model's actual response text (empty on error). */
    val actual: String,
    /** `true` if all [BenchTask.expectedSubstrings] appear in [actual]. */
    val pass: Boolean,
    /** Wall-clock time from prompt dispatch to response in milliseconds. */
    val latencyMs: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    /** Non-null when the task failed due to an exception (e.g. network error). */
    val error: String? = null,
)

/** Aggregated results from one `kuml ai bench` run. */
public data class BenchReport(
    val provider: String,
    val model: String,
    val results: List<BenchTaskResult>,
) {
    val passed: Int get() = results.count { it.pass }
    val failed: Int get() = results.count { !it.pass }
    val total: Int get() = results.size
    val allPassed: Boolean get() = passed == total
}
