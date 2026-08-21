package dev.kuml.ai.vault

import ai.koog.prompt.llm.LLMProvider
import dev.kuml.ai.provider.BuiltInProviders
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

/**
 * Simulates an OS keystore backend that cannot answer a presence check at all (e.g. a
 * permanently denied Keychain/secret-tool consent prompt) — used to verify that
 * [ApiKeyVault.has] and the [MasterPasswordVaultBackend] decorator both propagate that tri-state
 * `null` faithfully rather than collapsing it into a definite `false` (the exact fail-destructive
 * bug this backend exists to guard against — see [KeyVaultBackend.has]'s KDoc).
 */
private class FakeVaultErrorBackend : KeyVaultBackend {
    override val displayName: String = "Fake (has() always errors)"

    override fun isAvailable(): Boolean = true

    override fun put(
        key: String,
        secret: String,
    ) {
        // no-op — this fake never actually stores anything
    }

    override fun get(key: String): String? = null

    override fun delete(key: String) {
        // no-op
    }

    override fun has(key: String): Boolean? = null
}

class ApiKeyVaultTest :
    FunSpec({

        test("put then get returns stored secret") {
            val vault = ApiKeyVault(FakeKeyVaultBackend())
            vault.put(provider = LLMProvider.OpenAI, key = "sk-test-123")
            vault.get(LLMProvider.OpenAI) shouldBe "sk-test-123"
        }

        test("delete removes secret idempotently") {
            val vault = ApiKeyVault(FakeKeyVaultBackend())
            vault.put(provider = LLMProvider.Anthropic, key = "ant-key-abc")
            vault.delete(LLMProvider.Anthropic)
            vault.get(LLMProvider.Anthropic).shouldBeNull()
            // Second delete is a no-op
            vault.delete(LLMProvider.Anthropic)
            vault.get(LLMProvider.Anthropic).shouldBeNull()
        }

        test("put then get then delete roundtrips for the Gonka provider marker") {
            // Regression proof: Gonka's marker is a private named `object` subclass of
            // LLMProvider (not a bare `LLMProvider(...)` constructor call), specifically so
            // KeyVaultBackend.keyFor's ::class.simpleName-based vault key stays distinct and
            // collision-free — this exercises that end-to-end through the real ApiKeyVault API.
            val vault = ApiKeyVault(FakeKeyVaultBackend())
            val gonka = BuiltInProviders.gonka().koogProvider!!
            vault.put(provider = gonka, key = "gonka-broker-key-123")
            vault.get(gonka) shouldBe "gonka-broker-key-123"
            vault.delete(gonka)
            vault.get(gonka).shouldBeNull()
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

        // ── has() tri-state — see KeyVaultBackend.has's KDoc and the fail-destructive review
        // finding on AiProviderSettingsState.keyPresence ────────────────────────────────────

        test("has() defaults to get() != null for a backend with no override") {
            val vault = ApiKeyVault(FakeKeyVaultBackend())
            vault.has(LLMProvider.OpenAI) shouldBe false
            vault.put(provider = LLMProvider.OpenAI, key = "sk-test-123")
            vault.has(LLMProvider.OpenAI) shouldBe true
            vault.delete(LLMProvider.OpenAI)
            vault.has(LLMProvider.OpenAI) shouldBe false
        }

        test("has() propagates a backend's null (vault error) faithfully — not collapsed to false") {
            val vault = ApiKeyVault(FakeVaultErrorBackend())
            vault.has(LLMProvider.OpenAI).shouldBeNull()
        }

        test("MasterPasswordVaultBackend.has() delegates straight to inner, bypassing decryption entirely") {
            val inner = FakeVaultErrorBackend()
            val wrapped = MasterPasswordVaultBackend.create(masterPassword = "hunter2".toCharArray(), inner = inner)

            // Reaches inner's tri-state while unlocked...
            wrapped.has("kuml.ai.openai.apiKey").shouldBeNull()

            // ...and still does after locking — has() never needs the derived key, unlike
            // get()/put(), which throw VaultUnavailable once locked (see checkNotLocked()).
            wrapped.lock()
            wrapped.has("kuml.ai.openai.apiKey").shouldBeNull()
        }
    })
