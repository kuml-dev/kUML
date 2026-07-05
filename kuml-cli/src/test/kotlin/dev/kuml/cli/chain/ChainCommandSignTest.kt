package dev.kuml.cli.chain

import com.github.ajalt.clikt.testing.test
import dev.kuml.runtime.chain.ModelSignature
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

/**
 * CLI-Tests für `kuml chain sign` und `kuml chain verify-sig` (V3.0.5).
 *
 * Hardhat Account #0 (deterministisch, breit dokumentiert):
 *   privKey = 0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80
 *   addr    = 0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
 */
private const val PRIV_KEY_0 = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
private const val ADDR_0 = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
private const val ADDR_1 = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"

private const val MODEL_CONTENT = "diagram(\"SignTest\") { class_(\"Node\") }\n"

class ChainCommandSignTest :
    StringSpec({

        // ── kuml chain sign ───────────────────────────────────────────────────

        "sign: writes default .sig file parseable as ModelSignature" {
            val modelFile = Files.createTempFile("kuml-sign-test", ".kuml.kts").toFile()
            try {
                modelFile.writeText(MODEL_CONTENT)
                val result =
                    ChainCommand().test(
                        listOf("sign", modelFile.absolutePath, "--private-key", PRIV_KEY_0),
                    )
                result.statusCode shouldBe 0

                val sigFile = java.io.File("${modelFile.absolutePath}.sig")
                try {
                    sigFile.exists() shouldBe true
                    // Must parse without exception
                    val sig = ModelSignature.fromJson(sigFile.readText())
                    sig.signature.size shouldBe 65
                    sig.signer shouldBe ADDR_0
                } finally {
                    sigFile.delete()
                }
            } finally {
                modelFile.delete()
            }
        }

        "sign --out: writes signature to custom path, not default path" {
            val modelFile = Files.createTempFile("kuml-sign-out", ".kuml.kts").toFile()
            val outFile = Files.createTempFile("kuml-sign-custom", ".sig").toFile()
            try {
                modelFile.writeText(MODEL_CONTENT)
                outFile.delete() // start from absent state
                val result =
                    ChainCommand().test(
                        listOf(
                            "sign",
                            modelFile.absolutePath,
                            "--private-key",
                            PRIV_KEY_0,
                            "--out",
                            outFile.absolutePath,
                        ),
                    )
                result.statusCode shouldBe 0
                outFile.exists() shouldBe true
                // Default path must NOT have been written
                java.io.File("${modelFile.absolutePath}.sig").exists() shouldBe false
                // Custom output must be valid JSON
                ModelSignature.fromJson(outFile.readText()).signature.size shouldBe 65
            } finally {
                modelFile.delete()
                outFile.delete()
            }
        }

        "sign: nonexistent model file → IO_ERROR (exit 4)" {
            val result =
                ChainCommand().test(
                    listOf("sign", "/no/such/file.kuml.kts", "--private-key", PRIV_KEY_0),
                )
            result.statusCode shouldBe 4
            result.stderr shouldContain "I/O error reading"
        }

        "sign: invalid private key → CHAIN_INVALID_SIGNATURE (exit 52)" {
            val modelFile = Files.createTempFile("kuml-sign-badkey", ".kuml.kts").toFile()
            try {
                modelFile.writeText(MODEL_CONTENT)
                val result =
                    ChainCommand().test(
                        listOf("sign", modelFile.absolutePath, "--private-key", "0xDEAD"),
                    )
                result.statusCode shouldBe 52
                result.stderr shouldContain "Invalid private key"
            } finally {
                modelFile.delete()
            }
        }

        "sign: stdout contains signer address" {
            val modelFile = Files.createTempFile("kuml-sign-addr", ".kuml.kts").toFile()
            try {
                modelFile.writeText(MODEL_CONTENT)
                val result =
                    ChainCommand().test(
                        listOf("sign", modelFile.absolutePath, "--private-key", PRIV_KEY_0),
                    )
                result.statusCode shouldBe 0
                result.output shouldContain ADDR_0
            } finally {
                modelFile.delete()
                java.io.File("${modelFile.absolutePath}.sig").delete()
            }
        }

        // ── kuml chain verify-sig ─────────────────────────────────────────────

        "verify-sig: freshly signed file → exit 0 and VALID in output" {
            val modelFile = Files.createTempFile("kuml-verify-sig-ok", ".kuml.kts").toFile()
            val sigFile = java.io.File("${modelFile.absolutePath}.sig")
            try {
                modelFile.writeText(MODEL_CONTENT)
                ChainCommand().test(listOf("sign", modelFile.absolutePath, "--private-key", PRIV_KEY_0))

                val result =
                    ChainCommand().test(listOf("verify-sig", modelFile.absolutePath))
                result.statusCode shouldBe 0
                result.output shouldContain "VALID"
            } finally {
                modelFile.delete()
                sigFile.delete()
            }
        }

        "verify-sig: malformed .sig JSON → CHAIN_INVALID_SIGNATURE (exit 52)" {
            val modelFile = Files.createTempFile("kuml-verify-sig-bad", ".kuml.kts").toFile()
            val sigFile = java.io.File("${modelFile.absolutePath}.sig")
            try {
                modelFile.writeText(MODEL_CONTENT)
                sigFile.writeText("{ not valid json at all }")

                val result =
                    ChainCommand().test(listOf("verify-sig", modelFile.absolutePath))
                result.statusCode shouldBe 52
                result.stderr shouldContain "Malformed signature file"
            } finally {
                modelFile.delete()
                sigFile.delete()
            }
        }

        "verify-sig: missing .sig file → IO_ERROR (exit 4)" {
            val modelFile = Files.createTempFile("kuml-verify-nosig", ".kuml.kts").toFile()
            try {
                modelFile.writeText(MODEL_CONTENT)
                // Do NOT create the .sig file
                val result =
                    ChainCommand().test(listOf("verify-sig", modelFile.absolutePath))
                result.statusCode shouldBe 4
                result.stderr shouldContain "I/O error reading"
            } finally {
                modelFile.delete()
            }
        }

        "verify-sig --expected-signer correct address → exit 0" {
            val modelFile = Files.createTempFile("kuml-verify-sig-exp-ok", ".kuml.kts").toFile()
            val sigFile = java.io.File("${modelFile.absolutePath}.sig")
            try {
                modelFile.writeText(MODEL_CONTENT)
                ChainCommand().test(listOf("sign", modelFile.absolutePath, "--private-key", PRIV_KEY_0))

                val result =
                    ChainCommand().test(
                        listOf("verify-sig", modelFile.absolutePath, "--expected-signer", ADDR_0),
                    )
                result.statusCode shouldBe 0
                result.output shouldContain "Signer matches expected address"
            } finally {
                modelFile.delete()
                sigFile.delete()
            }
        }

        "verify-sig --expected-signer wrong address → CHAIN_SIGNER_MISMATCH (exit 53)" {
            val modelFile = Files.createTempFile("kuml-verify-sig-exp-bad", ".kuml.kts").toFile()
            val sigFile = java.io.File("${modelFile.absolutePath}.sig")
            try {
                modelFile.writeText(MODEL_CONTENT)
                ChainCommand().test(listOf("sign", modelFile.absolutePath, "--private-key", PRIV_KEY_0))

                val result =
                    ChainCommand().test(
                        listOf("verify-sig", modelFile.absolutePath, "--expected-signer", ADDR_1),
                    )
                result.statusCode shouldBe 53
                result.stderr shouldContain "SIGNER MISMATCH"
            } finally {
                modelFile.delete()
                sigFile.delete()
            }
        }

        "verify-sig: signature from wrong model (tampered content) → CHAIN_INVALID_SIGNATURE (exit 52)" {
            val modelFile = Files.createTempFile("kuml-verify-sig-tamper", ".kuml.kts").toFile()
            val altModelFile = Files.createTempFile("kuml-verify-sig-alt", ".kuml.kts").toFile()
            val sigFile = java.io.File("${modelFile.absolutePath}.sig")
            try {
                modelFile.writeText(MODEL_CONTENT)
                altModelFile.writeText("diagram(\"Tampered\") { }\n")

                // Sign the original model, then try to verify against altModel
                ChainCommand().test(listOf("sign", modelFile.absolutePath, "--private-key", PRIV_KEY_0))
                // Copy .sig to altModel's default path
                val altSigFile = java.io.File("${altModelFile.absolutePath}.sig")
                altSigFile.writeText(sigFile.readText())

                val result =
                    ChainCommand().test(listOf("verify-sig", altModelFile.absolutePath))
                result.statusCode shouldBe 52
            } finally {
                modelFile.delete()
                altModelFile.delete()
                sigFile.delete()
                java.io.File("${altModelFile.absolutePath}.sig").delete()
            }
        }
    })
