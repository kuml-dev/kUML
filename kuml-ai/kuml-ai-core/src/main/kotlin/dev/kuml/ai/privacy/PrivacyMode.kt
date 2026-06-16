package dev.kuml.ai.privacy

import ai.koog.prompt.llm.LLMProvider

/**
 * Classification: which Koog providers are local-only (do not call external APIs)?
 *
 * In V3.0.22, only Ollama is local. Cloud providers (OpenAI, Anthropic, Google,
 * OpenRouter, DeepSeek, Bedrock, Mistral) are all remote.
 */
public object PrivacyMode {
    /**
     * Set of Koog LLMProvider instances that are considered local.
     * Privacy mode restricts execution to these providers only.
     */
    public val LOCAL_PROVIDERS: Set<LLMProvider> = setOf(LLMProvider.Ollama)

    /** True if the given provider does not send data to external APIs. */
    public fun isLocal(provider: LLMProvider): Boolean = provider in LOCAL_PROVIDERS
}
