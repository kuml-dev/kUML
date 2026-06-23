package dev.kuml.ai.provider

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider

/**
 * Factory functions for the four built-in providers.
 *
 * Each provider maps a kUML string id to a Koog LLMProvider + LLMClient factory.
 *
 * **V3.1.15 tree-shaking:** clients are instantiated reflectively so the four Koog
 * provider-client JARs are declared `runtimeOnly` in kuml-ai-core's build. A consumer
 * can exclude a specific client JAR (e.g. the Google client) and only that provider's
 * factory will fail at call time ([ClassNotFoundException]) rather than at startup.
 */
public object BuiltInProviders {
    private const val OPENAI_CLIENT_FQCN = "ai.koog.prompt.executor.clients.openai.OpenAILLMClient"
    private const val ANTHROPIC_CLIENT_FQCN = "ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient"
    private const val GOOGLE_CLIENT_FQCN = "ai.koog.prompt.executor.clients.google.GoogleLLMClient"
    private const val OLLAMA_CLIENT_FQCN = "ai.koog.prompt.executor.ollama.client.OllamaClient"

    /**
     * Reflective factory for clients that require a single String API key.
     * All three cloud clients (OpenAI, Anthropic, Google) expose a `(String)` constructor.
     */
    private fun reflectiveClientWithKey(
        fqcn: String,
        apiKey: String?,
    ): LLMClient {
        requireNotNull(apiKey) {
            "$fqcn requires an API key — use ApiKeyVault.put() to store one."
        }
        val cls = Class.forName(fqcn)
        val ctor = cls.getConstructor(String::class.java)
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(apiKey) as LLMClient
    }

    /** Reflective no-arg factory for OllamaClient (local provider — no API key). */
    private fun reflectiveOllamaClient(): LLMClient {
        val cls = Class.forName(OLLAMA_CLIENT_FQCN)
        val ctor = cls.getDeclaredConstructor()
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance() as LLMClient
    }

    public fun openAi(): KumlLlmProvider =
        KumlLlmProvider(
            id = "openai",
            displayName = "OpenAI",
            koogProvider = LLMProvider.OpenAI,
            isLocal = false,
            clientFactory = { key -> reflectiveClientWithKey(OPENAI_CLIENT_FQCN, key) },
            supportedModels = ModelCatalog.descriptorsFor("openai"),
        )

    public fun anthropic(): KumlLlmProvider =
        KumlLlmProvider(
            id = "anthropic",
            displayName = "Anthropic",
            koogProvider = LLMProvider.Anthropic,
            isLocal = false,
            clientFactory = { key -> reflectiveClientWithKey(ANTHROPIC_CLIENT_FQCN, key) },
            supportedModels = ModelCatalog.descriptorsFor("anthropic"),
        )

    public fun google(): KumlLlmProvider =
        KumlLlmProvider(
            id = "google",
            displayName = "Google Gemini",
            koogProvider = LLMProvider.Google,
            isLocal = false,
            clientFactory = { key -> reflectiveClientWithKey(GOOGLE_CLIENT_FQCN, key) },
            supportedModels = ModelCatalog.descriptorsFor("google"),
        )

    public fun ollama(): KumlLlmProvider =
        KumlLlmProvider(
            id = "ollama",
            displayName = "Ollama (local)",
            koogProvider = LLMProvider.Ollama,
            isLocal = true,
            clientFactory = { _ -> reflectiveOllamaClient() },
            supportedModels = emptyList(), // dynamic model ids — no static catalog
        )

    /** All four built-in providers as a list. */
    public fun all(): List<KumlLlmProvider> = listOf(openAi(), anthropic(), google(), ollama())
}
