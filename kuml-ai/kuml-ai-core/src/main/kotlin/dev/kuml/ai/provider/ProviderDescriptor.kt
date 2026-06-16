package dev.kuml.ai.provider

/**
 * Service-loader entry point for custom providers.
 *
 * In V3.0.22, no custom providers are registered — the built-in set
 * (OpenAI, Anthropic, Google, Ollama) covers all needs.
 *
 * Third-party modules can implement this interface and register their
 * implementation via META-INF/services/dev.kuml.ai.provider.ProviderDescriptor
 * to have their provider discovered by ProviderRegistry.discover() in V3.1+.
 */
public interface ProviderDescriptor {
    /** Convert this descriptor to a KumlLlmProvider for registration. */
    public fun toKumlProvider(): KumlLlmProvider
}
