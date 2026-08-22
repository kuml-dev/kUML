package dev.kuml.ai.provider

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider

/**
 * Factory functions for the five built-in providers.
 *
 * Each provider maps a kUML string id to a Koog LLMProvider + LLMClient factory.
 *
 * **V3.1.15 tree-shaking:** clients are instantiated reflectively so the provider-client
 * JARs are declared `runtimeOnly` in kuml-ai-core's build. A consumer can exclude a
 * specific client JAR (e.g. the Google client) and only that provider's factory will
 * fail at call time ([ClassNotFoundException]) rather than at startup.
 *
 * **V3.1.20 Koog 1.0.0:** The single-String constructors for cloud clients and the
 * no-arg constructor for OllamaClient were removed. Clients now require a
 * [ai.koog.http.client.KoogHttpClient.Factory] alongside the API key. We resolve the
 * factory via [ServiceLoader] on [ai.koog.http.client.KoogHttpClient.Factory] — the
 * Ktor implementation registers itself automatically in META-INF/services.
 *
 * **Gonka:** unlike the other four, Gonka's Koog `LLMProvider` marker and its `LLMClient`
 * ship in a single third-party jar (`de.betchvaia:koog-gonka`) rather than as a Koog-native
 * artifact. kUML constructs its own [LLMProvider] marker for Gonka instead of reflectively
 * loading koog-gonka's own — see [GonkaProviderMarker] for why that is still fully
 * interchangeable with koog-gonka's own marker at runtime.
 */
public object BuiltInProviders {
    private const val OPENAI_CLIENT_FQCN = "ai.koog.prompt.executor.clients.openai.OpenAILLMClient"
    private const val ANTHROPIC_CLIENT_FQCN = "ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient"
    private const val GOOGLE_CLIENT_FQCN = "ai.koog.prompt.executor.clients.google.GoogleLLMClient"
    private const val OLLAMA_CLIENT_FQCN = "ai.koog.prompt.executor.ollama.client.OllamaClient"
    private const val HTTP_CLIENT_FACTORY_FQCN = "ai.koog.http.client.KoogHttpClient\$Factory"

    // ── Gonka (decentralized/blockchain AI-compute network, ApiKey auth only) ──
    private const val GONKA_CLIENT_FQCN = "de.betchvaia.koog.gonka.GonkaLLMClient"
    private const val GONKA_AUTH_FQCN = "de.betchvaia.koog.gonka.GonkaAuth"
    private const val GONKA_AUTH_APIKEY_FQCN = "de.betchvaia.koog.gonka.GonkaAuth\$ApiKey"

    /** kUML's own provider id AND the `LLMProvider.id` string koog-gonka's own GonkaLLMProvider uses. */
    internal const val GONKA_PROVIDER_ID: String = "gonka"

    /**
     * kUML's own [LLMProvider] marker for Gonka — deliberately NOT a reflective load of
     * koog-gonka's own `GonkaLLMProvider` object. Koog's `LLMProvider.equals()`/`hashCode()`
     * are structural on (id, display) (verified by decompiling ai.koog:prompt-llm-jvm:1.0.0 —
     * NOT reference identity, NOT even a same-subclass check: `instanceof LLMProvider` only),
     * so this independently constructed instance is interchangeable with
     * `de.betchvaia.koog.gonka.GonkaLLMProvider` (which uses the identical id="gonka",
     * display="Gonka") for every lookup MultiLLMPromptExecutor's Map<LLMProvider, LLMClient>
     * performs. This keeps the Gonka *marker* resolvable with zero compile-time dependency on
     * koog-gonka, matching how LLMProvider.OpenAI/Anthropic/Google/Ollama are "just always
     * there" via koog-agents-jvm (api) — only the *client* (below) is behind runtimeOnly +
     * reflection.
     *
     * Declared as a private named `object` subclass (rather than a bare `LLMProvider(...)`
     * constructor call) purely so [KeyVaultBackend.keyFor]'s `::class.simpleName`-based vault
     * key stays unique and stable ("GonkaProviderMarker") instead of colliding with any other
     * plain-constructed `LLMProvider` instance under the shared simpleName "LLMProvider".
     */
    private object GonkaProviderMarker : LLMProvider(GONKA_PROVIDER_ID, "Gonka")

    private val GONKA_PROVIDER: LLMProvider = GonkaProviderMarker

    /**
     * Resolves a [KoogHttpClient.Factory] via ServiceLoader.
     * The Ktor implementation [ai.koog.http.client.ktor.KtorKoogHttpClient.Factory]
     * registers itself in META-INF/services/ai.koog.http.client.KoogHttpClient$Factory.
     * Throws [ClassNotFoundException] if neither the interface nor an implementation
     * is on the classpath.
     *
     * internal (not private): also used by [OllamaModelCatalog.fetchOllamaModelIds] (V3.2.x,
     * P3) — same package, same reflective-client convention, no reason to duplicate this.
     */
    internal fun resolveHttpClientFactory(): Any {
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
            clientFactory = { key -> reflectiveClientWithKey(fqcn = OPENAI_CLIENT_FQCN, apiKey = key) },
            supportedModels = ModelCatalog.descriptorsFor("openai"),
        )

    public fun anthropic(): KumlLlmProvider =
        KumlLlmProvider(
            id = "anthropic",
            displayName = "Anthropic",
            koogProvider = LLMProvider.Anthropic,
            isLocal = false,
            clientFactory = { key -> reflectiveClientWithKey(fqcn = ANTHROPIC_CLIENT_FQCN, apiKey = key) },
            supportedModels = ModelCatalog.descriptorsFor("anthropic"),
        )

    public fun google(): KumlLlmProvider =
        KumlLlmProvider(
            id = "google",
            displayName = "Google Gemini",
            koogProvider = LLMProvider.Google,
            isLocal = false,
            clientFactory = { key -> reflectiveClientWithKey(fqcn = GOOGLE_CLIENT_FQCN, apiKey = key) },
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

    /**
     * Reflective factory for GonkaLLMClient. Unlike the four Koog-native clients, Gonka's
     * marker and client ship in the same jar, and its constructor shape differs (single
     * GonkaAuth.ApiKey arg, not (apiKey, KoogHttpClient.Factory)) — so this does not reuse
     * [reflectiveClientWithKey]. The broker base URL always comes from koog-gonka's own
     * GonkaAuth.DEFAULT_BROKER_URL constant (read reflectively, never from user input) —
     * there is no baseUrl override surface here, closing off base-URL-injection/SSRF risk.
     * Only ApiKey auth is wired — GonkaAuth.Wallet is intentionally never referenced (see
     * KDoc on [gonka] below).
     *
     * SECURITY NOTE: `auth` below holds the plaintext API key. koog-gonka's `GonkaAuth.ApiKey`
     * is an upstream Kotlin data class with a compiler-generated `toString()` that renders the
     * key verbatim — never log, print, or string-interpolate `auth` (or any exception that
     * might wrap it) anywhere in this function or its callers.
     */
    private fun reflectiveGonkaClient(apiKey: String?): LLMClient {
        requireNotNull(apiKey) {
            "$GONKA_CLIENT_FQCN requires an API key — use ApiKeyVault.put() to store one."
        }
        val brokerUrl = Class.forName(GONKA_AUTH_FQCN).getField("DEFAULT_BROKER_URL").get(null) as String
        val apiKeyCls = Class.forName(GONKA_AUTH_APIKEY_FQCN)
        val auth = apiKeyCls.getConstructor(String::class.java, String::class.java).newInstance(apiKey, brokerUrl)
        val clientCtor = Class.forName(GONKA_CLIENT_FQCN).getConstructor(apiKeyCls)
        @Suppress("UNCHECKED_CAST")
        return clientCtor.newInstance(auth) as LLMClient
    }

    /**
     * Gonka — decentralized/blockchain-based AI-compute network, OpenAI-compatible API.
     * Cloud provider (see [dev.kuml.ai.privacy.PrivacyMode] — deliberately NOT added to
     * `LOCAL_PROVIDERS`): calls leave the user's machine to third-party, network-selected
     * compute hosts, which is strictly worse for privacy than a single named corporate API,
     * not better — there is no single accountable counterparty. Only ApiKey (Broker) auth is
     * wired — GonkaAuth.Wallet auth is NOT used: its dispatch path throws
     * UnsupportedOperationException by design (Gonka's own upstream wallet-signing protocol
     * isn't ready). Do not add a Wallet code path in this or any future wave without
     * re-checking koog-gonka upstream.
     */
    public fun gonka(): KumlLlmProvider =
        KumlLlmProvider(
            id = GONKA_PROVIDER_ID,
            displayName = "Gonka",
            koogProvider = GONKA_PROVIDER,
            isLocal = false,
            clientFactory = { key -> reflectiveGonkaClient(apiKey = key) },
            supportedModels = emptyList(), // dynamic, network-hosted model catalog — like Ollama, no static table
        )

    /** All five built-in providers as a list. */
    public fun all(): List<KumlLlmProvider> = listOf(openAi(), anthropic(), google(), ollama(), gonka())
}
