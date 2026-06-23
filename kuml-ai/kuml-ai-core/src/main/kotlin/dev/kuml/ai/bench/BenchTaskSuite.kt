package dev.kuml.ai.bench

/**
 * Default benchmark task suite for `kuml ai bench`.
 *
 * Tasks are designed to be runnable with local Ollama models (llama3.2, qwen2.5, mistral)
 * without any kUML-specific tooling — they test general instruction-following and
 * structured output capability relevant to kUML modelling workflows.
 */
public object BenchTaskSuite {
    /** All default tasks (ordered by complexity). */
    public val all: List<BenchTask> =
        listOf(
            BenchTask(
                id = "uml-class-basic",
                systemPrompt = "You are a UML modelling assistant. Answer concisely.",
                userPrompt = "Name three core elements of a UML class diagram.",
                expectedSubstrings = listOf("class", "attribute"),
            ),
            BenchTask(
                id = "c4-context-elements",
                systemPrompt = "You are a software architecture assistant. Answer concisely.",
                userPrompt = "What are the four diagram types in the C4 model? List them.",
                expectedSubstrings = listOf("Context", "Container"),
            ),
            BenchTask(
                id = "dsl-kotlin-syntax",
                systemPrompt = "You are a Kotlin DSL assistant. Answer concisely.",
                userPrompt = "In a Kotlin DSL, how do you typically define a lambda with receiver?",
                expectedSubstrings = listOf("fun", "{"),
            ),
            BenchTask(
                id = "json-output",
                systemPrompt = "You output only valid JSON. No prose, no markdown fences, only raw JSON.",
                userPrompt = "Return a JSON object with keys \"name\" (string) and \"version\" (number 1).",
                expectedSubstrings = listOf("\"name\"", "\"version\""),
            ),
            BenchTask(
                id = "uml-sequence-elements",
                systemPrompt = "You are a UML modelling assistant. Answer concisely.",
                userPrompt = "Name two participants in a UML sequence diagram and what arrow type represents a synchronous call.",
                expectedSubstrings = listOf("actor", "->"),
            ),
        )

    /** Return at most [limit] tasks from the suite (first N in order). */
    public fun take(limit: Int): List<BenchTask> = all.take(limit.coerceAtLeast(1))
}
