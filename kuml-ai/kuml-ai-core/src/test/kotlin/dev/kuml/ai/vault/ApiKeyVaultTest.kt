package dev.kuml.ai.vault

import ai.koog.prompt.llm.LLMProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/** In-memory fake for tests — no filesystem, no OS keystore. */
private class FakeKeyVaultBackend : KeyVaultBackend {
    private val store = mutableMapOf<String, String>()
    override val displayName: String = "Fake (in-memory)"

    override fun isAvailable(): Boolean = true

    override fun put(
        key: String,
        secret: String,
    ) {
        store[key] = secret
    }

    override fun get(key: String): String? = store[key]

    override fun delete(key: String) {
        store.remove(key)
    }
}

class ApiKeyVaultTest :
    FunSpec({

        test("put then get returns stored secret") {
            val vault = ApiKeyVault(FakeKeyVaultBackend())
            vault.put(LLMProvider.OpenAI, "sk-test-123")
            vault.get(LLMProvider.OpenAI) shouldBe "sk-test-123"
        }

        test("delete removes secret idempotently") {
            val vault = ApiKeyVault(FakeKeyVaultBackend())
            vault.put(LLMProvider.Anthropic, "ant-key-abc")
            vault.delete(LLMProvider.Anthropic)
            vault.get(LLMProvider.Anthropic).shouldBeNull()
            // Second delete is a no-op
            vault.delete(LLMProvider.Anthropic)
            vault.get(LLMProvider.Anthropic).shouldBeNull()
        }

        test("isFallback is true when running on PlainJsonFallbackBackend") {
            val tempDir = Files.createTempDirectory("vault-fallback-test")
            try {
                val vault = ApiKeyVault(PlainJsonFallbackBackend(tempDir.resolve("secrets.json")))
                vault.isFallback.shouldBeTrue()
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    })
