package dev.kuml.runtime.chain.evm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.time.Instant

class EvmBlockClockTest :
    StringSpec({

        "refresh reads finalized block number and timestamp" {
            runTest {
                val server = MockRpcServer()
                server.onMethod("eth_getBlockByNumber") {
                    rpcSuccess(
                        result = """{"number":"0x64","timestamp":"0x60000000","hash":"0xabc123"}""",
                    )
                }
                server.start()
                try {
                    val client = EvmJsonRpcClient(server.baseUrl())
                    val clock = EvmBlockClock(client, finalityTag = "finalized")
                    clock.refresh()
                    clock.currentBlock() shouldBe 100L
                    clock.currentTime() shouldBe Instant.ofEpochSecond(0x60000000L)
                    clock.currentBlockHash() shouldBe "0xabc123"
                } finally {
                    server.stop()
                }
            }
        }

        "currentTime is Instant.EPOCH before first refresh" {
            val server = MockRpcServer()
            server.onMethod("eth_getBlockByNumber") {
                rpcSuccess(result = """{"number":"0x1","timestamp":"0x1","hash":"0x0"}""")
            }
            server.start()
            try {
                val client = EvmJsonRpcClient(server.baseUrl())
                val clock = EvmBlockClock(client)
                // Before any refresh
                clock.currentTime() shouldBe Instant.EPOCH
                clock.currentBlock() shouldBe 0L
                clock.currentBlockHash() shouldBe null
            } finally {
                server.stop()
            }
        }
    })
