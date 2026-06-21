package dev.kuml.runtime.chain.move

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class MoveAddressTest :
    StringSpec({

        "of with full 64-char hex address returns normalized lowercase" {
            val full = "0x" + "A".repeat(64)
            val addr = MoveAddress.of(full)
            addr.toString() shouldBe "0x" + "a".repeat(64)
        }

        "of with short address pads with leading zeros" {
            val addr = MoveAddress.of("0x1")
            addr.toString() shouldBe "0x" + "0".repeat(63) + "1"
        }

        "of with mixed-case hex normalizes to lowercase" {
            val addr = MoveAddress.of("0xABCD")
            addr.toString() shouldBe "0x" + "0".repeat(60) + "abcd"
        }

        "toBytes returns 32 bytes" {
            val addr = MoveAddress.of("0x1")
            addr.toBytes().size shouldBe 32
            addr.toBytes()[31] shouldBe 0x01.toByte()
        }

        "toBytes for full address matches expected bytes" {
            val addr = MoveAddress.of("0x" + "01".repeat(32))
            val bytes = addr.toBytes()
            bytes.size shouldBe 32
            bytes.all { it == 0x01.toByte() } shouldBe true
        }

        "of rejects address with invalid hex chars" {
            shouldThrow<IllegalArgumentException> { MoveAddress.of("0xGG") }
        }

        "of rejects address without 0x prefix" {
            shouldThrow<IllegalArgumentException> { MoveAddress.of("foo") }
        }

        "of rejects address with hex part longer than 64 chars" {
            shouldThrow<IllegalArgumentException> { MoveAddress.of("0x" + "1".repeat(65)) }
        }

        "of rejects empty string" {
            shouldThrow<IllegalArgumentException> { MoveAddress.of("") }
        }

        "isValid returns true for valid short address" {
            MoveAddress.isValid("0x1") shouldBe true
        }

        "isValid returns true for full 64-char address" {
            MoveAddress.isValid("0x" + "f".repeat(64)) shouldBe true
        }

        "isValid returns false for invalid address" {
            MoveAddress.isValid("nothex") shouldBe false
            MoveAddress.isValid("0x") shouldBe false
            MoveAddress.isValid("0x" + "1".repeat(65)) shouldBe false
        }
    })
