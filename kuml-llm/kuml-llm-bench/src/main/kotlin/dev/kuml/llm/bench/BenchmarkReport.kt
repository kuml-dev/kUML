package dev.kuml.llm.bench

public data class BenchmarkReport(
    val results: List<TaskResult>,
    val backend: String,
    val model: String,
) {
    public val totalTasks: Int get() = results.size
    public val successfulTasks: Int get() = results.count { it.valid }
    public val successRate: Double get() = if (totalTasks == 0) 0.0 else successfulTasks.toDouble() / totalTasks

    /** Generate a Markdown report. */
    public fun toMarkdown(): String {
        val sb = StringBuilder()
        sb.appendLine("# kUML LLM Benchmark Report")
        sb.appendLine()
        sb.appendLine("**Model:** $model  ")
        sb.appendLine("**Backend:** $backend  ")
        sb.appendLine("**Date:** ${java.time.LocalDate.now()}")
        sb.appendLine()
        sb.appendLine("## Summary")
        sb.appendLine()
        sb.appendLine("| Metric | Value |")
        sb.appendLine("|---|---|")
        sb.appendLine("| Total tasks | $totalTasks |")
        sb.appendLine("| Successful | $successfulTasks |")
        sb.appendLine("| Success rate | ${"%.1f".format(successRate * 100)}% |")
        sb.appendLine()

        // Per-tool breakdown
        sb.appendLine("## Results by Tool")
        sb.appendLine()
        sb.appendLine("| Tool | Tasks | Success | Rate |")
        sb.appendLine("|---|---|---|---|")
        for (tool in BenchmarkTool.entries) {
            val toolResults = results.filter { it.tool == tool }
            if (toolResults.isEmpty()) continue
            val successes = toolResults.count { it.valid }
            val rate = "%.1f%%".format(successes.toDouble() / toolResults.size * 100)
            sb.appendLine("| ${tool.name} | ${toolResults.size} | $successes | $rate |")
        }
        sb.appendLine()

        // Per-language breakdown
        sb.appendLine("## Results by Language")
        sb.appendLine()
        sb.appendLine("| Language | Tasks | Success | Rate |")
        sb.appendLine("|---|---|---|---|")
        for (lang in BenchmarkLanguage.entries) {
            val langResults = results.filter { it.language == lang }
            if (langResults.isEmpty()) continue
            val successes = langResults.count { it.valid }
            val rate = "%.1f%%".format(successes.toDouble() / langResults.size * 100)
            sb.appendLine("| ${lang.name} | ${langResults.size} | $successes | $rate |")
        }
        sb.appendLine()

        // Per-task detail
        sb.appendLine("## Task Detail")
        sb.appendLine()
        sb.appendLine("| Task | Tool | Lang | Type | Valid | Message |")
        sb.appendLine("|---|---|---|---|---|---|")
        for (r in results) {
            val status = if (r.valid) "✅" else "❌"
            sb.appendLine("| ${r.taskId} | ${r.tool} | ${r.language} | ${r.diagramType} | $status | ${r.validationMessage.take(60)} |")
        }
        sb.appendLine()
        return sb.toString()
    }
}
