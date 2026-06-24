package dev.kuml.runtime.chain.cosmos

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class Base64CodecTest :
    StringSpec({
        "encode and decode round-trip" {
            val original = "hello cosmos".toByteArray(Charsets.UTF_8)
            val encoded = Base64Codec.encode(original)
            val decoded = Base64Codec.decode(encoded)
            decoded shouldBe original
        }

        "encode empty bytes" {
            Base64Codec.encode(ByteArray(0)) shouldBe ""
        }

        "decode empty string" {
            Base64Codec.decode("").size shouldBe 0
        }

        "decode known base64 value" {
            // "test" in base64 is "dGVzdA=="
            val decoded = Base64Codec.decode("dGVzdA==")
            String(decoded, Charsets.UTF_8) shouldBe "test"
        }

        "decode invalid base64 throws IllegalArgumentException" {
            shouldThrow<IllegalArgumentException> {
                Base64Codec.decode("not!valid!base64!!!")
            }
        }

        "round-trip with binary data" {
            val original = ByteArray(32) { it.toByte() }
            val decoded = Base64Codec.decode(Base64Codec.encode(original))
            decoded shouldBe original
        }
    })
