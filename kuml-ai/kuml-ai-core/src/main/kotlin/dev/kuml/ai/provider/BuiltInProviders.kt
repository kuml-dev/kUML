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
 *
 * **V3.1.20 Koog 1.0.0:** The single-String constructors for cloud clients and the
 * no-arg constructor for OllamaClient were removed. Clients now require a
 * [ai.koog.http.client.KoogHttpClient.Factory] alongside the API key. We resolve the
 * factory via [ServiceLoader] on [ai.koog.http.client.KoogHttpClient.Factory] — the
 * Ktor implementation registers itself automatically in META-INF/services.
 */
public object BuiltInProviders {
    private const val OPENAI_CLIENT_FQCN = "ai.koog.prompt.executor.clients.openai.OpenAILLMClient"
    private const val ANTHROPIC_CLIENT_FQCN = "ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient"
    private const val GOOGLE_CLIENT_FQCN = "ai.koog.prompt.executor.clients.google.GoogleLLMClient"
    private const val OLLAMA_CLIENT_FQCN = "ai.koog.prompt.executor.ollama.client.OllamaClient"
    private const val HTTP_CLIENT_FACTORY_FQCN = "ai.koog.http.client.KoogHttpClient\$Factory"

    /**
     * Resolves a [KoogHttpClient.Factory] via ServiceLoader.
     * The Ktor implementation [ai.koog.http.client.ktor.KtorKoogHttpClient.Factory]
     * registers itself in META-INF/services/ai.koog.http.client.KoogHttpClient$Factory.
     * Throws [ClassNotFoundException] if neither the interface nor an implementation
     * is on the classpath.
     */
    private fun resolveHttpClientFactory(): Any {
        val factoryClass = Class.forName(HTTP_CLIENT_FACTORY_FQCN)
        val loader =
            java.util.ServiceLoader.load(
                @Suppress("UNCHECKED_CAST") (factoryClass as Class<Any>),
            )
        return loader.firstOrNull()
            ?: error(
                "No KoogHttpClient.Factory implementation found via ServiceLoader. " +
                    "Add 'ai.koog:http-client-ktor-jvm' to the runtime classpath.",
            )
    }

    /**
     * Reflective factory for cloud clients that accept (String apiKey, KoogHttpClient.Factory).
     * Koog 1.0.0: constructors changed — the (String) single-arg constructor was removed.
     */
    private fun reflectiveClientWithKey(
        fqcn: String,
        apiKey: String?,
    ): LLMClient {
        requireNotNull(apiKey) {
            "$fqcn requires an API key — use ApiKeyVault.put() to store one."
        }
        val cls = Class.forName(fqcn)
        val factory = resolveHttpClientFactory()
        val factoryClass = Class.forName(HTTP_CLIENT_FACTORY_FQCN)
        val ctor = cls.getConstructor(String::class.java, factoryClass)
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(apiKey, factory) as LLMClient
    }

    /**
     * Reflective factory for OllamaClient.
     * Koog 1.0.0: no-arg constructor removed — use (KoogHttpClient.Factory) instead.
     */
    private fun reflectiveOllamaClient(): LLMClient {
        val cls = Class.forName(OLLAMA_CLIENT_FQCN)
        val factory = resolveHttpClientFactory()
        val factoryClass = Class.forName(HTTP_CLIENT_FACTORY_FQCN)
        val ctor = cls.getConstructor(factoryClass)
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(factory) as LLMClient
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
