package dev.kuml.ai.vault

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class PlainJsonFallbackBackendTest :
    FunSpec({

        lateinit var tempDir: java.nio.file.Path

        beforeTest {
            tempDir = Files.createTempDirectory("kuml-plain-json-test")
        }

        afterTest { (_, _) ->
            tempDir.toFile().deleteRecursively()
        }

        test("plain json fallback round trip via temp file") {
            val backend = PlainJsonFallbackBackend(tempDir.resolve("secrets.json"))
            backend.put(key = "my-key", secret = "my-secret")
            backend.get("my-key") shouldBe "my-secret"
        }

        test("plain json fallback emits warning on first use") {
            // We cannot easily capture the SLF4J warning in a unit test,
            // but we can verify the backend is always available and functional.
            val backend = PlainJsonFallbackBackend(tempDir.resolve("secrets-warn-test.json"))
            backend.isAvailable() shouldBe true
            backend.put(key = "warn-key", secret = "warn-value")
            backend.get("warn-key") shouldBe "warn-value"
            backend.delete("warn-key")
            backend.get("warn-key").shouldBeNull()
        }

        // ── has() tri-state — regression coverage for the fail-destructive review finding:
        // the default KeyVaultBackend.has() (get(key) != null) cannot distinguish "no secrets
        // file yet" from "secrets file exists but is unreadable/corrupted", because readMap()
        // swallows all read/parse exceptions into an empty map. The override must tell these
        // apart itself. ─────────────────────────────────────────────────────────────────────

        test("has() returns false when the storage file does not exist yet") {
            val backend = PlainJsonFallbackBackend(tempDir.resolve("never-written.json"))
            backend.has("some-key") shouldBe false
        }

        test("has() returns true for a stored key and false for an absent key, once the file exists") {
            val backend = PlainJsonFallbackBackend(tempDir.resolve("secrets-has-test.json"))
            backend.put(key = "present-key", secret = "value")
            backend.has("present-key") shouldBe true
            backend.has("absent-key") shouldBe false
        }

        test("has() returns null — not false — when the storage file exists but fails to parse") {
            // Simulates a corrupted secrets.json (e.g. truncated by a crash mid-write) or any
            // other read failure that readMap() would otherwise silently collapse to "empty".
            val storagePath = tempDir.resolve("corrupted-secrets.json")
            Files.writeString(storagePath, "{ not valid json ]]]", StandardCharsets.UTF_8)
            val backend = PlainJsonFallbackBackend(storagePath)

            backend.has("any-key").shouldBeNull()
        }

        // ── Cross-backend behavioural contract (V3.7.4) — see KeyVaultBackendContract's KDoc.
        // Runs unconditionally in CI: this backend needs no external tool or OS keystore.
        keyVaultBackendContract(backendName = "PlainJsonFallbackBackend") {
            PlainJsonFallbackBackend(
                storagePath = Files.createTempDirectory("kuml-plain-json-contract-test").resolve("secrets.json"),
            )
        }
    })
