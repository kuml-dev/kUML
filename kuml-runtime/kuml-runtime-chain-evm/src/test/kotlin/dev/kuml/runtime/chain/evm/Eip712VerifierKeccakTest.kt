package dev.kuml.runtime.chain.evm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.security.MessageDigest

class Eip712VerifierKeccakTest :
    StringSpec({

        "keccak256 of empty input matches Ethereum known vector" {
            val result = Eip712Verifier.keccak256(ByteArray(0))
            val hex = result.joinToString("") { "%02x".format(it) }
            hex shouldBe "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"
        }

        "keccak256 of 'abc' matches known vector" {
            val result = Eip712Verifier.keccak256("abc".toByteArray(Charsets.UTF_8))
            val hex = result.joinToString("") { "%02x".format(it) }
            hex shouldBe "4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45"
        }

        "keccak256 differs from JVM SHA3-256 for same input" {
            // This test is the guard: proves we're NOT using NIST SHA3 (0x06 padding).
            // They differ because Ethereum uses 0x01 padding, NIST SHA3 uses 0x06.
            val input = "hello world".toByteArray(Charsets.UTF_8)
            val ourKeccak = Eip712Verifier.keccak256(input).joinToString("") { "%02x".format(it) }
            val jvmSha3 = MessageDigest.getInstance("SHA3-256").digest(input).joinToString("") { "%02x".format(it) }
            ourKeccak shouldNotBe jvmSha3
        }
    })
