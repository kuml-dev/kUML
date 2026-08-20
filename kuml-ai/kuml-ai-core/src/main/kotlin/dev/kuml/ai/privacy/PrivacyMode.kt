package dev.kuml.ai.privacy

import ai.koog.prompt.llm.LLMProvider

/**
 * Classification: which Koog providers are local-only (do not call external APIs)?
 *
 * In V3.0.22, only Ollama is local. Cloud providers (OpenAI, Anthropic, Google,
 * OpenRouter, DeepSeek, Bedrock, Mistral) are all remote.
 *
 * **Gonka is deliberately classified as a cloud/remote provider**, not local — despite
 * having no single named corporate API behind it. Gonka dispatches inference to
 * decentralized, network-selected compute hosts the user does not control or
 * necessarily know the identity of, which is strictly worse for privacy than a single
 * accountable counterparty (there is no one privacy policy bound to whichever node the
 * network assigns). Ollama is local because it is a daemon on `localhost` — nothing
 * leaves the machine. Gonka fails that test completely, so it stays subject to the same
 * `privacyMode`-blocks-cloud-providers behavior as OpenAI/Anthropic/Google.
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
