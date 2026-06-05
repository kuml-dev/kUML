package dev.kuml.llm.bench

import dev.kuml.llm.core.LlmBackend
import dev.kuml.llm.core.LlmException
import dev.kuml.llm.core.LlmMessage

public object BenchmarkRunner {
    public fun run(
        tasks: List<BenchmarkTask>,
        backend: LlmBackend,
        onProgress: (TaskResult) -> Unit = {},
    ): BenchmarkReport {
        val results = mutableListOf<TaskResult>()
        for (task in tasks) {
            val start = System.currentTimeMillis()
            val llmResp =
                try {
                    backend.complete(
                        messages = listOf(LlmMessage("user", task.userPrompt)),
                        systemPrompt = task.systemPrompt,
                    )
                } catch (e: LlmException) {
                    val result =
                        TaskResult(
                            taskId = task.id,
                            tool = task.tool,
                            language = task.language,
                            diagramType = task.diagramType,
                            llmResponse = "",
                            valid = false,
                            validationMessage = "LLM error: ${e.message}",
                            inputTokens = 0,
                            outputTokens = 0,
                            durationMs = System.currentTimeMillis() - start,
                        )
                    results += result
                    onProgress(result)
                    continue
                }
            val (valid, message) =
                when (task.tool) {
                    BenchmarkTool.KUML -> BenchmarkValidator.validateKuml(llmResp.content)
                    BenchmarkTool.PLANTUML -> BenchmarkValidator.validatePlantUml(llmResp.content)
                    BenchmarkTool.MERMAID -> BenchmarkValidator.validateMermaid(llmResp.content)
                }
            val result =
                TaskResult(
                    taskId = task.id,
                    tool = task.tool,
                    language = task.language,
                    diagramType = task.diagramType,
                    llmResponse = llmResp.content,
                    valid = valid,
                    validationMessage = message,
                    inputTokens = llmResp.inputTokens,
                    outputTokens = llmResp.outputTokens,
                    durationMs = System.currentTimeMillis() - start,
                )
            results += result
            onProgress(result)
        }
        return BenchmarkReport(results = results, backend = backend.id, model = backend.model)
    }
}
