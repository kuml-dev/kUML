package dev.kuml.runtime.chain.spec

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

private val json =
    Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

/** Matches a canonical ABI signature: Name(types) — no spaces, no param names. */
private val abiSignatureRegex = Regex("""^[A-Za-z_][A-Za-z0-9_]*\([a-z0-9,\[\]]*\)$""")

class ContractEventSpecTest :
    StringSpec({
        "V1 event signatures follow ABI format Name(types)" {
            KumlBackedContractSpec.V1.events.forEach { event ->
                abiSignatureRegex.matches(event.signature).shouldBeTrue()
                event.signature.startsWith("${event.name}(").shouldBeTrue()
            }
        }

        "ContractEventSpec JSON roundtrip" {
            val event = KumlBackedContractSpec.V1.events.first { it.name == "ModelUpdated" }
            val encoded = json.encodeToString(ContractEventSpec.serializer(), event)
            val decoded = json.decodeFromString(ContractEventSpec.serializer(), encoded)
            decoded shouldBe event
        }
    })
