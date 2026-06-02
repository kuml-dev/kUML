package dev.kuml.llm.bench

/** Target tool for a benchmark task. */
public enum class BenchmarkTool { KUML, PLANTUML, MERMAID }

/** Natural language used in the task prompt. */
public enum class BenchmarkLanguage { DE, EN }

/** Type of diagram being described. */
public enum class DiagramType { CLASS, C4_SYSTEM_CONTEXT }

/**
 * A single benchmark task: a prompt sent to the LLM + validation of the response.
 */
public data class BenchmarkTask(
    val id: String,
    val tool: BenchmarkTool,
    val language: BenchmarkLanguage,
    val diagramType: DiagramType,
    val systemPrompt: String,
    val userPrompt: String,
    /** IDs/names of expected classifiers or elements in a valid response. */
    val expectedElements: List<String> = emptyList(),
)

/** Result of running one task. */
public data class TaskResult(
    val taskId: String,
    val tool: BenchmarkTool,
    val language: BenchmarkLanguage,
    val diagramType: DiagramType,
    val llmResponse: String,
    val valid: Boolean,
    val validationMessage: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val durationMs: Long,
)
