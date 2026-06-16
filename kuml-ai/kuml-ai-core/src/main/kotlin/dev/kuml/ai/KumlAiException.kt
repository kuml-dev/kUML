package dev.kuml.ai

import ai.koog.prompt.llm.LLMProvider

/** Sealed exception family for AI-core failure modes. Each subtype carries an enum-stable error code. */
public sealed class KumlAiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** Privacy mode is on but a cloud provider was requested. Code: KUML-AI-E-001. */
    public class PrivacyModeViolation(
        public val attemptedProvider: LLMProvider,
    ) : KumlAiException(
            "KUML-AI-E-001: Privacy mode is enabled — provider '$attemptedProvider' is not allowed. " +
                "Only local providers (Ollama) may be used.",
        )

    /** Required API key is missing from vault. Code: KUML-AI-E-002. */
    public class MissingApiKey(
        public val provider: LLMProvider,
    ) : KumlAiException(
            "KUML-AI-E-002: No API key stored for provider '$provider'. " +
                "Use ApiKeyVault.put(provider, key).",
        )

    /** Vault backend not available on this OS. Code: KUML-AI-E-003. */
    public class VaultUnavailable(
        message: String,
        cause: Throwable? = null,
    ) : KumlAiException("KUML-AI-E-003: $message", cause)

    /** Settings file is malformed or unsupported schema version. Code: KUML-AI-E-004. */
    public class SettingsCorrupted(
        message: String,
        cause: Throwable? = null,
    ) : KumlAiException("KUML-AI-E-004: $message", cause)

    /** Provider is enabled in settings but not registered in ProviderRegistry. Code: KUML-AI-E-005. */
    public class UnknownProvider(
        public val providerId: String,
    ) : KumlAiException(
            "KUML-AI-E-005: Provider '$providerId' is enabled in settings but not registered.",
        )
}
