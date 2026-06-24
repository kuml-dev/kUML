package dev.kuml.runtime.chain.wasm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class WasmAdapterFactoryTest :
    FunSpec({

        // -------------------------------------------------------------------------
        // SubstrateInkFactory.supportsChainId
        // -------------------------------------------------------------------------

        test("SubstrateInkFactory: supports known ink! chains") {
            val factory = SubstrateInkFactory()
            listOf("polkadot", "kusama", "astar", "shiden", "shibuya", "aleph-zero", "phala", "substrate-contracts").forEach { id ->
                factory.supportsChainId(id) shouldBe true
            }
        }

        test("SubstrateInkFactory: supports 'ink:*' prefix") {
            SubstrateInkFactory().supportsChainId("ink:foo") shouldBe true
        }

        test("SubstrateInkFactory: supports 'substrate:*' prefix") {
            SubstrateInkFactory().supportsChainId("substrate:bar") shouldBe true
        }

        test("SubstrateInkFactory: does not support ethereum") {
            SubstrateInkFactory().supportsChainId("ethereum") shouldBe false
        }

        test("SubstrateInkFactory: does not support cosmwasm:juno-1") {
            SubstrateInkFactory().supportsChainId("cosmwasm:juno-1") shouldBe false
        }

        // -------------------------------------------------------------------------
        // CosmWasmBridgeFactory.supportsChainId
        // -------------------------------------------------------------------------

        test("CosmWasmBridgeFactory: supports 'cosmwasm:juno-1'") {
            CosmWasmBridgeFactory().supportsChainId("cosmwasm:juno-1") shouldBe true
        }

        test("CosmWasmBridgeFactory: supports 'cosmwasm:osmosis-1'") {
            CosmWasmBridgeFactory().supportsChainId("cosmwasm:osmosis-1") shouldBe true
        }

        test("CosmWasmBridgeFactory: does not support 'polkadot'") {
            CosmWasmBridgeFactory().supportsChainId("polkadot") shouldBe false
        }

        test("CosmWasmBridgeFactory: create() without cosmos module → IllegalStateException with explanation") {
            // The cosmos module is NOT on the test classpath of this module (no compile dependency).
            // CosmWasmChainAdapter uses the package dev.kuml.runtime.chain.cosmos.cosmwasm.CosmWasmChainAdapter,
            // but the bridge looks for dev.kuml.runtime.chain.cosmos.CosmWasmChainAdapter (top-level).
            val ex =
                shouldThrow<IllegalStateException> {
                    CosmWasmBridgeFactory().create()
                }
            ex.message shouldContain "kuml-runtime-chain-cosmos"
        }

        // -------------------------------------------------------------------------
        // ServiceLoader integration
        // -------------------------------------------------------------------------

        test("forChain('astar') → SubstrateInkFactory via ServiceLoader") {
            val factory = WasmAdapterFactory.forChain("astar")
            factory?.factoryId shouldBe "substrate-ink"
        }

        test("availableFactoryIds contains 'substrate-ink'") {
            val ids = WasmAdapterFactory.availableFactoryIds()
            ids.contains("substrate-ink") shouldBe true
        }
    })
