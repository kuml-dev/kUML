package dev.kuml.ai.provider

import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMProvider

/**
 * Factory functions for the four built-in providers.
 * Each provider maps a kUML string id to a Koog LLMProvider + LLMClient factory.
 */
public object BuiltInProviders {
    public fun openAi(): KumlLlmProvider =
        KumlLlmProvider(
            id = "openai",
            displayName = "OpenAI",
            koogProvider = LLMProvider.OpenAI,
            isLocal = false,
            clientFactory = { key ->
                requireNotNull(key) { "OpenAI requires an API key — use ApiKeyVault.put(LLMProvider.OpenAI, key)" }
                OpenAILLMClient(key)
            },
        )

    public fun anthropic(): KumlLlmProvider =
        KumlLlmProvider(
            id = "anthropic",
            displayName = "Anthropic",
            koogProvider = LLMProvider.Anthropic,
            isLocal = false,
            clientFactory = { key ->
                requireNotNull(key) { "Anthropic requires an API key — use ApiKeyVault.put(LLMProvider.Anthropic, key)" }
                AnthropicLLMClient(key)
            },
        )

    public fun google(): KumlLlmProvider =
        KumlLlmProvider(
            id = "google",
            displayName = "Google Gemini",
            koogProvider = LLMProvider.Google,
            isLocal = false,
            clientFactory = { key ->
                requireNotNull(key) { "Google requires an API key — use ApiKeyVault.put(LLMProvider.Google, key)" }
                GoogleLLMClient(key)
            },
        )

    public fun ollama(): KumlLlmProvider =
        KumlLlmProvider(
            id = "ollama",
            displayName = "Ollama (local)",
            koogProvider = LLMProvider.Ollama,
            isLocal = true,
            clientFactory = { _ -> OllamaClient() },
        )

    /** All four built-in providers as a list. */
    public fun all(): List<KumlLlmProvider> = listOf(openAi(), anthropic(), google(), ollama())
}
