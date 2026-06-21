package dev.kuml.runtime.chain.multi

import dev.kuml.runtime.chain.ContractIdentity
import dev.kuml.runtime.chain.ModelHasher
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class MultiChainContractIdentityTest :
    StringSpec({

        "from: gleiche Hashes → consistent=true" {
            val hash = ModelHasher.hashCanonical(ModelHasher.canonicalize("model { state(\"A\") }\n"))
            val identity =
                MultiChainContractIdentity.from(
                    linkedMapOf(
                        "evm" to identity("0xEVM", hash),
                        "sui" to identity("0xSUI", hash.copyOf()),
                    ),
                )
            identity.consistent.shouldBeTrue()
        }

        "from: unterschiedliche Hashes → consistent=false" {
            val hash1 = ModelHasher.hashCanonical(ModelHasher.canonicalize("model { state(\"A\") }\n"))
            val hash2 = ModelHasher.hashCanonical(ModelHasher.canonicalize("model { state(\"X\") }\n"))
            val identity =
                MultiChainContractIdentity.from(
                    linkedMapOf(
                        "evm" to identity("0xEVM", hash1),
                        "sui" to identity("0xSUI", hash2),
                    ),
                )
            identity.consistent.shouldBeFalse()
        }

        "from: primaryModelHash entspricht dem Hash der ersten Chain in der LinkedHashMap" {
            val hashEvm = ModelHasher.hashCanonical(ModelHasher.canonicalize("model { state(\"E\") }\n"))
            val hashSui = ModelHasher.hashCanonical(ModelHasher.canonicalize("model { state(\"S\") }\n"))
            val identity =
                MultiChainContractIdentity.from(
                    linkedMapOf(
                        "evm" to identity("0xEVM", hashEvm),
                        "sui" to identity("0xSUI", hashSui),
                    ),
                )
            // Erste Chain ist "evm" → primaryModelHash == hashEvm
            identity.primaryModelHash.contentEquals(hashEvm).shouldBeTrue()
            identity.primaryModelHash.contentEquals(hashSui) shouldBe false
        }

        "from(emptyMap) → wirft IllegalArgumentException" {
            shouldThrow<IllegalArgumentException> {
                MultiChainContractIdentity.from(emptyMap())
            }
        }
    })

// ── Hilfsfunktion ──────────────────────────────────────────────────────────────

private fun identity(
    address: String,
    hash: ByteArray,
): ContractIdentity =
    ContractIdentity(
        address = address,
        modelHash = hash,
        modelUri = "ipfs://QmFake",
        schemaVersion = 1,
    )
