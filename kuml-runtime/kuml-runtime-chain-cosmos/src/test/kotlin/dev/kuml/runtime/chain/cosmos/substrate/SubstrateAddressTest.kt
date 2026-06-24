package dev.kuml.runtime.chain.cosmos.substrate

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SubstrateAddressTest :
    StringSpec({
        "isValid accepts known good SS58 address 5GrwvaEF" {
            // Alice's well-known SS58 address in Polkadot
            SubstrateAddress.isValid("5GrwvaEFyWSMkHMtFEEWBFVuQ8bMm9Q6wn9AKTjrMN2n3JGN") shouldBe true
        }

        "isValid rejects address with invalid base58 char 0" {
            SubstrateAddress.isValid("0GrwvaEFyWSMkHMtFEEWBFVuQ8bMm9Q6wn9AKTjrMN2n3JGN") shouldBe false
        }

        "isValid rejects address with invalid base58 char O" {
            SubstrateAddress.isValid("OGrwvaEFyWSMkHMtFEEWBFVuQ8bMm9Q6wn9AKTjrMN2n3JGN") shouldBe false
        }

        "isValid rejects address with invalid base58 char I" {
            SubstrateAddress.isValid("IGrwvaEFyWSMkHMtFEEWBFVuQ8bMm9Q6wn9AKTjrMN2n3JGN") shouldBe false
        }

        "isValid rejects address with invalid base58 char l" {
            SubstrateAddress.isValid("lGrwvaEFyWSMkHMtFEEWBFVuQ8bMm9Q6wn9AKTjrMN2n3JGN") shouldBe false
        }

        "isValid rejects empty string" {
            SubstrateAddress.isValid("") shouldBe false
        }

        "isValid rejects string longer than 64 chars" {
            SubstrateAddress.isValid("A".repeat(65)) shouldBe false
        }

        "isValid rejects address with wrong decoded length" {
            // A base58 string that decodes to wrong length (too short)
            SubstrateAddress.isValid("ABC") shouldBe false
        }

        "base58Decode leading 1s become leading zero bytes" {
            // '111' has 3 leading 1s → 3 zero prefix bytes + 1 byte from intData=0 = 4 bytes total
            val result = SubstrateAddress.base58Decode("111")
            result?.size shouldBe 4
            result?.all { it.toInt() == 0 } shouldBe true
        }

        "base58Decode returns null for invalid characters" {
            SubstrateAddress.base58Decode("0ABC") shouldBe null
        }

        "base58Decode consistency" {
            // A known base58 round-trip: "2g" decodes to 0x61 ('a')
            val result = SubstrateAddress.base58Decode("2g")
            result shouldBe byteArrayOf(0x61)
        }
    })
