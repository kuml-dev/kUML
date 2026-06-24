package dev.kuml.runtime.chain.cosmos.cosmwasm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CosmosAddressTest :
    StringSpec({
        // Valid bech32 addresses (generated with proper BIP-173 checksum, payload=all zeros)
        val validCosmos = "cosmos1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnrql8a" // 20-byte account
        val validJuno = "juno1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq93ryqp" // 20-byte account
        val validContract32 = "cosmos1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq0fr2sh" // 32-byte contract

        "valid bech32 account address cosmos1 returns true" {
            CosmosAddress.isValid(validCosmos) shouldBe true
        }

        "valid bech32 address with different hrp juno returns true" {
            CosmosAddress.isValid(validJuno) shouldBe true
        }

        "valid 32-byte contract address returns true" {
            CosmosAddress.isValid(validContract32) shouldBe true
        }

        "isValid rejects mixed case" {
            CosmosAddress.isValid("Cosmos1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnrql8u") shouldBe false
        }

        "isValid rejects missing separator 1" {
            CosmosAddress.isValid("cosmosqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnrql8u") shouldBe false
        }

        "isValid rejects empty hrp" {
            CosmosAddress.isValid("1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnrql8u") shouldBe false
        }

        "isValid rejects too short address" {
            CosmosAddress.isValid("a1b") shouldBe false
        }

        "isValid rejects too long address (>90)" {
            CosmosAddress.isValid("a" + "1" + "q".repeat(90)) shouldBe false
        }

        "isValid rejects address with invalid bech32 alphabet chars b i o" {
            // 'b', 'i', 'o' are not in bech32 alphabet
            CosmosAddress.isValid("cosmos1bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb") shouldBe false
        }

        "isValid rejects wrong checksum" {
            // Flip last character of a valid address
            val badChecksum = validCosmos.dropLast(1) + "q"
            CosmosAddress.isValid(badChecksum) shouldBe false
        }

        "isValidContract accepts 32-byte contract address (dataLen=58)" {
            // 32-byte contract: "cosmos" + 1 + 52 data + 6 checksum = 65 chars, dataLen=58
            CosmosAddress.isValidContract(validContract32) shouldBe true
        }

        "isValidContract accepts 20-byte account address (dataLen=38)" {
            // 20-byte accounts are also accepted by isValidContract (dataLen=38)
            CosmosAddress.isValidContract(validCosmos) shouldBe true
        }

        "isValidContract rejects invalid bech32" {
            CosmosAddress.isValidContract("notabech32") shouldBe false
        }

        "isValid rejects empty string" {
            CosmosAddress.isValid("") shouldBe false
        }

        "isValid accepts all-uppercase of a valid address" {
            // All-uppercase is allowed (same as all-lowercase)
            CosmosAddress.isValid(validCosmos.uppercase()) shouldBe true
        }
    })
