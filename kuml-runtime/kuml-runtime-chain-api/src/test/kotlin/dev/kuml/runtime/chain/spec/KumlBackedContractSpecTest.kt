package dev.kuml.runtime.chain.spec

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

private val json =
    Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

class KumlBackedContractSpecTest :
    StringSpec({
        "V1 has schemaVersion 1" {
            KumlBackedContractSpec.V1.schemaVersion shouldBe 1
        }

        "V1 exposes exactly the three reader functions" {
            val names =
                KumlBackedContractSpec.V1.functions
                    .map { it.name }
                    .toSet()
            names shouldBe setOf("modelHash", "modelUri", "schemaVersion")
            KumlBackedContractSpec.V1.functions.forEach { fn ->
                fn.stateMutability shouldBe "view"
            }
        }

        "V1 function signatures match EvmChainAdapter selectors" {
            // Each function signature must be exactly "<name>()" — no extra params.
            // This prevents drift between the spec and the EvmChainAdapter selector strings.
            KumlBackedContractSpec.V1.functions.forEach { fn ->
                fn.signature shouldBe "${fn.name}()"
            }
        }

        "V1 JSON roundtrip preserves all fields" {
            val encoded = json.encodeToString(KumlBackedContractSpec.serializer(), KumlBackedContractSpec.V1)
            val decoded = json.decodeFromString(KumlBackedContractSpec.serializer(), encoded)
            decoded shouldBe KumlBackedContractSpec.V1
        }

        "bundled resource json deserialises to V1" {
            val stream =
                checkNotNull(
                    KumlBackedContractSpecTest::class.java
                        .getResourceAsStream("/dev/kuml/runtime/chain/spec/kuml-backed-contract-v1.json"),
                ) { "Resource kuml-backed-contract-v1.json not found on classpath" }
            val text = stream.bufferedReader().readText()
            val parsed = json.decodeFromString(KumlBackedContractSpec.serializer(), text)
            parsed shouldBe KumlBackedContractSpec.V1
        }
    })
