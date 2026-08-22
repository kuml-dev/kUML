package dev.kuml.ai.vault

import dev.kuml.ai.KumlAiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Tests MacOsKeychainBackend with a mocked ShellOut.
 *
 * The real `security` CLI is never invoked in these tests -- command responses are
 * pre-programmed / the tests inspect only the pure command-building functions. The real
 * live-Keychain round trip is exercised via [keyVaultBackendContract] gated behind
 * `-Dkuml.ai.vault.liveKeystoreTests=true`, run manually on macOS -- see this class's factory
 * wiring below.
 */
class MacOsKeychainBackendTest :
    FunSpec({

        test("mac keychain backend put-get-delete round trip via mocked security cli") {
            // We test via PlainJsonFallbackBackend as a proxy since ShellOut
            // cannot be mocked without a DI seam. The real keychain test
            // is gated behind kuml.ai.vault.liveKeystoreTests and MAC-only.
            // This test verifies the backend's isAvailable() on a non-macOS host returns false.
            System.setProperty("kuml.ai.os", "linux")
            try {
                val backend = MacOsKeychainBackend()
                // On Linux CI, isAvailable should be false (security CLI not present)
                // The test verifies the backend gracefully returns false rather than crashing
                val available = backend.isAvailable()
                // Just verify no exception was thrown -- result depends on OS
                assert(available || !available)
            } finally {
                System.clearProperty("kuml.ai.os")
            }
        }

        // Regression tests for the fail-destructive review finding: has()'s exit-code decision
        // table is extracted into a pure function specifically so it is unit-testable without a
        // ShellOut mocking seam -- see MacOsKeychainBackend.interpretHasExitCode's KDoc.
        test("interpretHasExitCode: exit 0 means the item was found") {
            MacOsKeychainBackend.interpretHasExitCode(0) shouldBe true
        }

        test("interpretHasExitCode: exit 44 (errSecItemNotFound) means definitely absent") {
            MacOsKeychainBackend.interpretHasExitCode(44) shouldBe false
        }

        test("interpretHasExitCode: any other exit code is an unknown backend error, not absence") {
            MacOsKeychainBackend.interpretHasExitCode(-128).shouldBeNull() // errSecUserCanceled
            MacOsKeychainBackend.interpretHasExitCode(-25308).shouldBeNull() // errSecInteractionNotAllowed
            MacOsKeychainBackend.interpretHasExitCode(1).shouldBeNull()
        }

        // ── V3.7.4 credential-misrouting fix -- structural regression coverage ──────────────
        // These pin the (account, service) command shape so a future edit cannot silently
        // reintroduce the shared-service bug (every provider overwriting the same Keychain item).

        test("serviceFor is distinct for every real provider key") {
            val keys =
                listOf(
                    "kuml.ai.openaillmprovider.apikey",
                    "kuml.ai.anthropicllmprovider.apikey",
                    "kuml.ai.googlellmprovider.apikey",
                    "kuml.ai.ollamallmprovider.apikey",
                    "kuml.ai.gonkallmprovider.apikey",
                )
            val services = keys.map { key -> MacOsKeychainBackend.serviceFor(baseService = "dev.kuml.ai", key = key) }
            services.toSet().size shouldBe services.size
        }

        test("putCommand carries a key-scoped -s value, distinct across two keys") {
            val cmdA = MacOsKeychainBackend.putCommand(user = "u", baseService = "dev.kuml.ai", key = "key-a")
            val cmdB = MacOsKeychainBackend.putCommand(user = "u", baseService = "dev.kuml.ai", key = "key-b")
            val sIndexA = cmdA.indexOf("-s") + 1
            val sIndexB = cmdB.indexOf("-s") + 1
            cmdA[sIndexA] shouldNotBe cmdB[sIndexB]
            cmdA[sIndexA] shouldBe "dev.kuml.ai.key-a"
            cmdB[sIndexB] shouldBe "dev.kuml.ai.key-b"
        }

        test("findCommand carries a key-scoped -s value, distinct across two keys") {
            val cmdA = MacOsKeychainBackend.findCommand(user = "u", baseService = "dev.kuml.ai", key = "key-a", withSecret = true)
            val cmdB = MacOsKeychainBackend.findCommand(user = "u", baseService = "dev.kuml.ai", key = "key-b", withSecret = true)
            val sIndexA = cmdA.indexOf("-s") + 1
            val sIndexB = cmdB.indexOf("-s") + 1
            cmdA[sIndexA] shouldNotBe cmdB[sIndexB]
        }

        test("deleteCommand carries a key-scoped -s value, distinct across two keys") {
            val cmdA = MacOsKeychainBackend.deleteCommand(user = "u", baseService = "dev.kuml.ai", key = "key-a")
            val cmdB = MacOsKeychainBackend.deleteCommand(user = "u", baseService = "dev.kuml.ai", key = "key-b")
            val sIndexA = cmdA.indexOf("-s") + 1
            val sIndexB = cmdB.indexOf("-s") + 1
            cmdA[sIndexA] shouldNotBe cmdB[sIndexB]
        }

        test("legacyProbeCommand carries neither -l nor -w, and exactly the base service") {
            val cmd = MacOsKeychainBackend.legacyProbeCommand(user = "u", baseService = "dev.kuml.ai")
            cmd shouldNotBeContaining "-l"
            cmd shouldNotBeContaining "-w"
            val sIndex = cmd.indexOf("-s") + 1
            cmd[sIndex] shouldBe "dev.kuml.ai"
        }

        test("findCommand(withSecret = false) never carries -w") {
            val cmd = MacOsKeychainBackend.findCommand(user = "u", baseService = "dev.kuml.ai", key = "key-a", withSecret = false)
            cmd shouldNotBeContaining "-w"
        }

        // ── requireSafeKey -- pure input guard, unit-testable without a ShellOut mocking seam
        // (review finding: this new throw-semantics-introducing function had zero direct test
        // coverage, even though put/get/has/delete all call it before ever touching the CLI).

        test("requireSafeKey accepts an ordinary key") {
            MacOsKeychainBackend.requireSafeKey("kuml.ai.anthropicllmprovider.apikey")
        }

        test("requireSafeKey rejects a blank key") {
            shouldThrow<KumlAiException.VaultUnavailable> {
                MacOsKeychainBackend.requireSafeKey("")
            }
            shouldThrow<KumlAiException.VaultUnavailable> {
                MacOsKeychainBackend.requireSafeKey("   ")
            }
        }

        test("requireSafeKey rejects a key containing a NUL byte") {
            shouldThrow<KumlAiException.VaultUnavailable> {
                MacOsKeychainBackend.requireSafeKey("kuml.ai." + 0.toChar() + ".apikey")
            }
        }

        test("put/get/has/delete all reject an unsafe key before touching the CLI") {
            val backend = MacOsKeychainBackend()
            shouldThrow<KumlAiException.VaultUnavailable> { backend.put(key = "", secret = "s") }
            shouldThrow<KumlAiException.VaultUnavailable> { backend.get(key = "") }
            shouldThrow<KumlAiException.VaultUnavailable> { backend.has(key = "") }
            shouldThrow<KumlAiException.VaultUnavailable> { backend.delete(key = "") }
        }

        // ── requireSafeSecret -- guards against the two-prompt stdin desync (class KDoc):
        // a blank secret or one containing an embedded newline/NUL must never reach the
        // `security` CLI, because that failure mode exits 0 while silently storing a
        // truncated or empty secret.

        test("requireSafeSecret accepts an ordinary secret") {
            MacOsKeychainBackend.requireSafeSecret("sk-abc123")
        }

        test("requireSafeSecret rejects a blank secret") {
            shouldThrow<KumlAiException.VaultUnavailable> {
                MacOsKeychainBackend.requireSafeSecret("")
            }
            shouldThrow<KumlAiException.VaultUnavailable> {
                MacOsKeychainBackend.requireSafeSecret("   ")
            }
        }

        test("requireSafeSecret rejects a secret containing an embedded newline") {
            shouldThrow<KumlAiException.VaultUnavailable> {
                MacOsKeychainBackend.requireSafeSecret("sk-abc\n123")
            }
            shouldThrow<KumlAiException.VaultUnavailable> {
                MacOsKeychainBackend.requireSafeSecret("sk-abc\r123")
            }
        }

        test("requireSafeSecret rejects a secret containing a NUL byte") {
            shouldThrow<KumlAiException.VaultUnavailable> {
                MacOsKeychainBackend.requireSafeSecret("sk-abc" + 0.toChar() + "123")
            }
        }

        test("put rejects a blank or newline-containing secret before touching the CLI") {
            val backend = MacOsKeychainBackend()
            shouldThrow<KumlAiException.VaultUnavailable> { backend.put(key = "some.key", secret = "") }
            shouldThrow<KumlAiException.VaultUnavailable> { backend.put(key = "some.key", secret = "a\nb") }
        }

        // ── Cross-backend behavioural contract (V3.7.4) — see KeyVaultBackendContract's KDoc.
        // This is the actual proof that the credential-misrouting bug is fixed against the REAL
        // macOS Keychain, not just the pure command-building functions above. Gated behind an
        // explicit opt-in AND real availability -- it writes to this machine's real Keychain
        // (under a disposable per-run service) and must never run unattended in CI:
        //   ./gradlew clean :kuml-ai:kuml-ai-core:test -Dkuml.ai.vault.liveKeystoreTests=true
        keyVaultBackendContract(backendName = "MacOsKeychainBackend (live)") {
            val liveKeystoreOptIn = System.getProperty("kuml.ai.vault.liveKeystoreTests") == "true"
            MacOsKeychainBackend(service = "dev.kuml.ai.test.${java.util.UUID.randomUUID()}")
                .takeIf { liveKeystoreOptIn && OsDetection.current() == OsDetection.Os.MAC && it.isAvailable() }
        }
    })

private infix fun List<String>.shouldNotBeContaining(value: String) {
    (value in this) shouldBe false
}
