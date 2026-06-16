package dev.kuml.ai.privacy

import ai.koog.prompt.llm.LLMProvider
import dev.kuml.ai.KumlAiException

/**
 * Stateless enforcer that guards against cloud API calls when privacy mode is on.
 *
 * Throws [KumlAiException.PrivacyModeViolation] (KUML-AI-E-001) when a cloud
 * provider is attempted while [privacyMode] is true.
 *
 * The streaming path performs the same eager check before building the Flow,
 * so callers see the error immediately and not on the first collect.
 */
public class PrivacyEnforcer(
    public val privacyMode: Boolean,
) {
    /**
     * Returns [provider] unchanged on success.
     * Throws [KumlAiException.PrivacyModeViolation] when [privacyMode] is true
     * and the provider is not in [PrivacyMode.LOCAL_PROVIDERS].
     */
    public fun guard(provider: LLMProvider): LLMProvider {
        if (privacyMode && !PrivacyMode.isLocal(provider)) {
            throw KumlAiException.PrivacyModeViolation(provider)
        }
        return provider
    }

    /** True if the provider is permitted under the current mode. */
    public fun isAllowed(provider: LLMProvider): Boolean = !privacyMode || PrivacyMode.isLocal(provider)
}
