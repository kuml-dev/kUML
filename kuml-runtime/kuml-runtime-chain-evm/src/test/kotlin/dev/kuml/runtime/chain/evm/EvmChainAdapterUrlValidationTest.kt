package dev.kuml.runtime.chain.evm

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain

/**
 * Tests für [EvmChainAdapter.validateRpcUrl] — SSRF-Schutzlogik.
 *
 * Da [validateRpcUrl] `internal` ist, ist sie aus demselben Gradle-Modul
 * (Test-Sourceset) direkt aufrufbar.
 */
class EvmChainAdapterUrlValidationTest :
    StringSpec({

        // ── Schema-Prüfung ───────────────────────────────────────────────────────

        "rejects file:// scheme" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    EvmChainAdapter.validateRpcUrl("file:///etc/passwd")
                }
            ex.message shouldContain "http or https"
        }

        "rejects ftp:// scheme" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    EvmChainAdapter.validateRpcUrl("ftp://example.com/rpc")
                }
            ex.message shouldContain "http or https"
        }

        "rejects gopher:// scheme" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    EvmChainAdapter.validateRpcUrl("gopher://evil.example.com:70/1")
                }
            ex.message shouldContain "http or https"
        }

        // ── IPv4 Private/Loopback-Ranges ─────────────────────────────────────────

        "rejects IPv4 loopback 127.0.0.1" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    EvmChainAdapter.validateRpcUrl("http://127.0.0.1:8545")
                }
            ex.message shouldContain "SSRF"
        }

        "rejects IPv4 loopback 127.0.0.255" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    EvmChainAdapter.validateRpcUrl("http://127.0.0.255:8545")
                }
            ex.message shouldContain "SSRF"
        }

        "rejects AWS metadata link-local 169.254.169.254" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    EvmChainAdapter.validateRpcUrl("http://169.254.169.254/latest/meta-data/")
                }
            ex.message shouldContain "SSRF"
        }

        "rejects site-local 10.0.0.1" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    EvmChainAdapter.validateRpcUrl("http://10.0.0.1:8545")
                }
            ex.message shouldContain "SSRF"
        }

        "rejects site-local 192.168.1.1" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    EvmChainAdapter.validateRpcUrl("http://192.168.1.1:8545")
                }
            ex.message shouldContain "SSRF"
        }

        "rejects site-local 172.16.0.1" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    EvmChainAdapter.validateRpcUrl("http://172.16.0.1:8545")
                }
            ex.message shouldContain "SSRF"
        }

        "rejects site-local 172.31.255.255" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    EvmChainAdapter.validateRpcUrl("http://172.31.255.255:8545")
                }
            ex.message shouldContain "SSRF"
        }

        // ── IPv6 Private/Loopback ─────────────────────────────────────────────────

        "rejects IPv6 loopback ::1" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    EvmChainAdapter.validateRpcUrl("http://[::1]:8545")
                }
            ex.message shouldContain "SSRF"
        }

        // ── Gültige öffentliche Endpunkte ─────────────────────────────────────────

        "accepts public hostname (no DNS lookup performed)" {
            shouldNotThrowAny {
                EvmChainAdapter.validateRpcUrl("https://eth-mainnet.example.com:443")
            }
        }

        "accepts public IP literal 1.2.3.4" {
            shouldNotThrowAny {
                EvmChainAdapter.validateRpcUrl("https://1.2.3.4:8545")
            }
        }

        "accepts https scheme with path" {
            shouldNotThrowAny {
                EvmChainAdapter.validateRpcUrl("https://rpc.ankr.com/eth/sometoken")
            }
        }

        "accepts http scheme (non-TLS, e.g. for local dev network)" {
            shouldNotThrowAny {
                EvmChainAdapter.validateRpcUrl("http://203.0.113.1:8545")
            }
        }
    })
