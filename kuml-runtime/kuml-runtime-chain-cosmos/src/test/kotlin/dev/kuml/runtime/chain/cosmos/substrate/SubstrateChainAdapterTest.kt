package dev.kuml.runtime.chain.cosmos.substrate

import dev.kuml.runtime.chain.cosmos.MockRpcServer
import dev.kuml.runtime.chain.cosmos.rpcSuccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class SubstrateChainAdapterTest :
    FunSpec({
        // Alice's well-known SS58 address (passes SubstrateAddress.isValid)
        val contractAddr = "5GrwvaEFyWSMkHMtFEEWBFVuQ8bMm9Q6wn9AKTjrMN2n3JGN"

        lateinit var server: MockRpcServer

        beforeTest {
            server = MockRpcServer()
            server.start()
        }

        afterTest { server.stop() }

        fun makeAdapter(): SubstrateChainAdapter =
            SubstrateChainAdapter(
                urlValidator = SubstrateUrlValidator.NoOp,
                pollIntervalMillis = 10L,
                clientFactory = { SubstrateRpcClient(server.baseUrl()) },
            )

        fun setupIdentityResponse(
            modelHash: ByteArray = ByteArray(4) { (it + 1).toByte() },
            modelUri: String = "ipfs://test",
            schemaVersion: Int = 1,
        ) {
            val uriBytes = modelUri.toByteArray(Charsets.UTF_8)
            val hashCompact = (modelHash.size shl 2).toByte()
            val uriCompact = (uriBytes.size shl 2).toByte()
            val bytes =
                byteArrayOf(0, hashCompact) + modelHash +
                    byteArrayOf(uriCompact) + uriBytes +
                    byteArrayOf(schemaVersion.toByte(), 0, 0, 0)
            val hex = "0x" + bytes.joinToString("") { "%02x".format(it) }
            server.onMethod("state_call") {
                rpcSuccess(result = """{"result":{"Ok":{"data":"$hex"}}}""")
            }
        }

        fun setupFinalizedHeight(height: Long) {
            server.onMethod("chain_getFinalizedHead") { rpcSuccess(result = "\"0xhash\"") }
            server.onMethod("chain_getHeader") { rpcSuccess(result = """{"number":"0x${height.toString(16)}"}""") }
        }

        fun setupBlockHash(hash: String = "0xblockhash") {
            server.onMethod("chain_getBlockHash") { rpcSuccess(result = "\"$hash\"") }
        }

        fun setupSystemEvents(hex: String = "") {
            server.onMethod("state_getStorage") { rpcSuccess(result = if (hex.isEmpty()) "null" else "\"$hex\"") }
        }

        test("connect returns ContractIdentity with correct fields") {
            runTest {
                val modelHash = byteArrayOf(1, 2, 3, 4)
                setupIdentityResponse(modelHash, "ipfs://abc", 2)
                val adapter = makeAdapter()
                val identity = adapter.connect(server.baseUrl(), contractAddr)
                identity.address shouldBe contractAddr
                identity.modelUri shouldBe "ipfs://abc"
                identity.schemaVersion shouldBe 2
                identity.modelHash shouldBe modelHash
            }
        }

        test("connect throws InvalidContractAddress for invalid SS58") {
            runTest {
                val adapter = makeAdapter()
                shouldThrow<SubstrateChainAdapterException.InvalidContractAddress> {
                    adapter.connect(server.baseUrl(), "notanss58address")
                }
            }
        }

        test("connect throws InvalidUrlException for SSRF URL with default validator") {
            runTest {
                val adapter =
                    SubstrateChainAdapter(
                        urlValidator = SubstrateUrlValidator.Default,
                        clientFactory = { SubstrateRpcClient(server.baseUrl()) },
                    )
                shouldThrow<SubstrateChainAdapterException.InvalidUrlException> {
                    adapter.connect("http://127.0.0.1:9999", contractAddr)
                }
            }
        }

        test("blockClock throws error before connect") {
            val adapter = makeAdapter()
            val ex =
                shouldThrow<IllegalStateException> {
                    adapter.blockClock()
                }
            ex.message shouldContain "connect()"
        }

        test("replay throws IllegalArgumentException for negative fromBlock") {
            runTest {
                setupIdentityResponse()
                setupFinalizedHeight(5L)
                val adapter = makeAdapter()
                adapter.connect(server.baseUrl(), contractAddr)
                shouldThrow<IllegalArgumentException> {
                    adapter.replay(-1L).toList()
                }
            }
        }

        test("replay returns empty when head is below fromBlock") {
            runTest {
                setupIdentityResponse()
                setupFinalizedHeight(0L)
                val adapter = makeAdapter()
                adapter.connect(server.baseUrl(), contractAddr)
                setupFinalizedHeight(0L)
                val events = adapter.replay(1L).toList()
                events shouldHaveSize 0
            }
        }

        test("replay iterates blocks and returns empty events when no matching ContractEmitted") {
            runTest {
                setupIdentityResponse()
                setupFinalizedHeight(3L)
                val adapter = makeAdapter()
                adapter.connect(server.baseUrl(), contractAddr)
                setupFinalizedHeight(3L)
                setupBlockHash()
                setupSystemEvents()
                val events = adapter.replay(1L).toList()
                events shouldHaveSize 0
            }
        }

        test("subscribe cold flow can be taken with 0 elements") {
            runTest {
                setupIdentityResponse()
                setupFinalizedHeight(5L)
                val adapter = makeAdapter()
                adapter.connect(server.baseUrl(), contractAddr)
                setupFinalizedHeight(5L)
                setupBlockHash()
                setupSystemEvents()
                // subscribe() returns a cold flow — each call yields an independent
                // flow instance; we don't collect it (would block on poll).
                val flow = adapter.subscribe()
                val flow2 = adapter.subscribe()
                (flow !== flow2) shouldBe true
            }
        }
    })
