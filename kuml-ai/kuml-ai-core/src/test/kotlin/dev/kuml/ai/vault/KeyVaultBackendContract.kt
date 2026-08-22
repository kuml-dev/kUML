package dev.kuml.ai.vault

import ai.koog.prompt.llm.LLMProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Behavioural contract that EVERY [KeyVaultBackend] must satisfy -- shared across the
 * backend-specific test specs instead of being re-written per backend. A regression test
 * written only against [MacOsKeychainBackend] would have caught V3.7.3's credential-misrouting
 * bug on macOS but would not have protected the other three backends from the same class of
 * mistake being reintroduced later; this contract is the actual deliverable for that concern.
 *
 * [factory] is invoked exactly ONCE, at contract-composition time -- not once per test. This
 * matters for backends like [MacOsKeychainBackend] and [LinuxSecretToolBackend], whose factories
 * (see the call sites in the corresponding `*BackendTest.kt` files) construct the backend with a
 * fresh random per-run service name; calling [factory] again per test would silently start each
 * test against a brand-new, empty service and defeat the whole point of the round-trip
 * assertions. A `null` result means the backend is not available in this environment (missing
 * CLI tool, wrong OS, live-keystore tests opted out) -- the contract then registers a single
 * explanatory skipped-style test instead of running (and failing to construct) the rest.
 */
internal fun FunSpec.keyVaultBackendContract(
    backendName: String,
    factory: () -> KeyVaultBackend?,
) {
    val keyA = KeyVaultBackend.keyFor(LLMProvider.OpenAI)
    val keyB = KeyVaultBackend.keyFor(LLMProvider.Anthropic)

    val backend: KeyVaultBackend? = factory()

    if (backend == null) {
        test("[$backendName] contract -- skipped (backend unavailable in this environment)") {
            // Intentionally empty: documents why the round-trip suite below did not run,
            // rather than silently omitting the backend from the test report entirely.
        }
        return
    }

    // Best-effort cleanup so a failed run does not leave the two probe keys behind in a
    // long-lived store (relevant for the live macOS/Linux keystore runs; harmless no-op
    // for the in-memory/temp-file backends, which are thrown away after the test anyway).
    afterSpec {
        runCatching { backend.delete(keyA) }
        runCatching { backend.delete(keyB) }
    }

    test("[$backendName] contract: put(a) and put(b) round-trip independently") {
        backend.put(key = keyA, secret = "SECRET_A")
        backend.put(key = keyB, secret = "SECRET_B")
        backend.get(keyA) shouldBe "SECRET_A"
        backend.get(keyB) shouldBe "SECRET_B"
    }

    test("[$backendName] contract: delete(a) removes only a, b and its has() survive") {
        backend.put(key = keyA, secret = "SECRET_A")
        backend.put(key = keyB, secret = "SECRET_B")

        backend.delete(keyA)

        backend.get(keyA).shouldBeNull()
        backend.get(keyB) shouldBe "SECRET_B"
        backend.has(keyB) shouldBe true
    }

    test("[$backendName] contract: has() is independent per key") {
        backend.delete(keyA)
        backend.delete(keyB)
        backend.put(key = keyA, secret = "SECRET_A")

        backend.has(keyA) shouldBe true
        backend.has(keyB) shouldBe false
    }

    test("[$backendName] contract: put(a, v1) then put(a, v2) updates a; b is unaffected") {
        backend.put(key = keyA, secret = "v1")
        backend.put(key = keyB, secret = "b-untouched")

        backend.put(key = keyA, secret = "v2")

        backend.get(keyA) shouldBe "v2"
        backend.get(keyB) shouldBe "b-untouched"
    }

    test("[$backendName] contract: delete of a never-set key does not throw") {
        backend.delete(keyA)
        backend.delete(keyA) // idempotent -- second delete also must not throw
    }
}
