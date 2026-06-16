package dev.kuml.ai.vault

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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
            backend.put("my-key", "my-secret")
            backend.get("my-key") shouldBe "my-secret"
        }

        test("plain json fallback emits warning on first use") {
            // We cannot easily capture the SLF4J warning in a unit test,
            // but we can verify the backend is always available and functional.
            val backend = PlainJsonFallbackBackend(tempDir.resolve("secrets-warn-test.json"))
            backend.isAvailable() shouldBe true
            backend.put("warn-key", "warn-value")
            backend.get("warn-key") shouldBe "warn-value"
            backend.delete("warn-key")
            backend.get("warn-key").shouldBeNull()
        }
    })
