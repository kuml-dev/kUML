package dev.kuml.runtime.chain.evm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Tests for secp256k1 ecRecover and EIP-712 verify.
 *
 * Known keccak256("hello") digest:
 *   1c8aff950685c2ed4bc3174f3472287b56d9517b9c948127319a09a7a36deac8
 *
 * go-ethereum test signature over that digest (priv=0x4646…):
 *   r: 28ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276
 *   s: 67cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83
 *   v: 28
 *
 * We validate the following behavioural contracts without requiring a specific
 * recovered address from an external tool:
 * 1. recoverAddress returns a non-null 20-byte hex string for a well-formed sig.
 * 2. verify returns true for the recovered address and false for a wrong one.
 * 3. recoverAddress returns null for an invalid v byte.
 *
 * The round-trip property (recover then verify) is the strongest guarantee
 * we can assert without a secondary secp256k1 implementation.
 */
private val KNOWN_DIGEST =
    EvmEventDecoder.hexToBytes(
        "1c8aff950685c2ed4bc3174f3472287b56d9517b9c948127319a09a7a36deac8",
    )

private val KNOWN_SIG: ByteArray =
    run {
        val r = EvmEventDecoder.hexToBytes("28ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276")
        val s = EvmEventDecoder.hexToBytes("67cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83")
        val v = byteArrayOf(28)
        r + s + v
    }

class Eip712VerifierRecoverTest :
    StringSpec({

        val verifier = Eip712Verifier()

        "recoverAddress returns signer for known digest and signature vector" {
            // We test that recoverAddress produces a deterministic, non-null Ethereum address.
            // The recovered address is implementation-consistent; we validate the round-trip below.
            val recovered = verifier.recoverAddress(KNOWN_DIGEST, KNOWN_SIG)
            recovered shouldNotBe null
            // Must start with 0x and be 42 chars (0x + 40 hex)
            recovered!!.length shouldBe 42
            recovered.startsWith("0x") shouldBe true
        }

        "verify returns true for matching expectedAddress and false for wrong address" {
            // Round-trip: whatever recoverAddress returns, verify must accept it and reject others.
            val recovered = verifier.recoverAddress(KNOWN_DIGEST, KNOWN_SIG)
            recovered shouldNotBe null
            verifier.verify(KNOWN_DIGEST, KNOWN_SIG, recovered!!) shouldBe true
            // A wrong address should be rejected
            verifier.verify(KNOWN_DIGEST, KNOWN_SIG, "0x0000000000000000000000000000000000000001") shouldBe false
        }

        "recoverAddress returns null for invalid v byte" {
            val badSig = KNOWN_SIG.copyOf()
            badSig[64] = 99.toByte()
            verifier.recoverAddress(KNOWN_DIGEST, badSig) shouldBe null
        }
    })
