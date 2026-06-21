package dev.kuml.runtime.chain.spec

import dev.kuml.runtime.chain.ModelHasher
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

private val json =
    Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

class ContractTestVectorTest :
    StringSpec({
        "STANDARD_VECTORS has at least 5 vectors" {
            ContractTestVector.STANDARD_VECTORS.size shouldBeGreaterThanOrEqualTo 5
        }

        "every vector's expectedHash matches ModelHasher recomputation" {
            ContractTestVector.STANDARD_VECTORS.forEach { v ->
                val recomputed = ModelHasher.hashCanonical(ModelHasher.canonicalize(v.modelSource))
                recomputed.contentEquals(v.expectedHash).shouldBeTrue()
                v.expectedHash.size shouldBe 32
            }
        }

        "every vector's expectedCanonical matches ModelHasher.canonicalize" {
            ContractTestVector.STANDARD_VECTORS.forEach { v ->
                ModelHasher.canonicalize(v.modelSource) shouldBe v.expectedCanonical
            }
        }

        "ContractTestVector JSON roundtrip via hex serializer preserves hash bytes" {
            val vector = ContractTestVector.STANDARD_VECTORS.first()
            val encoded = json.encodeToString(ContractTestVector.serializer(), vector)
            val decoded = json.decodeFromString(ContractTestVector.serializer(), encoded)
            decoded.expectedHash.contentEquals(vector.expectedHash).shouldBeTrue()
            decoded shouldBe vector
            // Assert the JSON contains the hash as a 64-char lowercase hex string, not as Int-array.
            val hex = vector.expectedHash.joinToString("") { "%02x".format(it) }
            encoded.contains("\"$hex\"").shouldBeTrue()
        }
    })
