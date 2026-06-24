package dev.kuml.runtime.chain.cosmos.cosmwasm

import dev.kuml.runtime.chain.cosmos.MockRpcServer
import dev.kuml.runtime.chain.cosmos.rpcSuccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.time.Instant

class CosmWasmBlockClockTest :
    StringSpec({
        lateinit var server: MockRpcServer
        lateinit var client: CosmWasmRpcClient

        beforeTest {
            server = MockRpcServer()
            server.start()
            client = CosmWasmRpcClient(server.baseUrl())
        }

        afterTest { server.stop() }

        "initial values are EPOCH and 0" {
            val clock = CosmWasmBlockClock(client)
            clock.currentTime() shouldBe Instant.EPOCH
            clock.currentBlock() shouldBe 0L
        }

        "refresh updates block and time from status response" {
            runTest {
                server.onMethod("status") {
                    rpcSuccess(result = """{"sync_info":{"latest_block_height":"42","latest_block_time":"2024-06-01T12:00:00Z"}}""")
                }
                val clock = CosmWasmBlockClock(client)
                clock.refresh()
                clock.currentBlock() shouldBe 42L
                clock.currentTime() shouldBe Instant.parse("2024-06-01T12:00:00Z")
            }
        }

        "refresh throws MalformedResponse when height missing" {
            runTest {
                server.onMethod("status") {
                    rpcSuccess(result = """{"sync_info":{"latest_block_time":"2024-06-01T12:00:00Z"}}""")
                }
                val clock = CosmWasmBlockClock(client)
                shouldThrow<CosmWasmChainAdapterException.MalformedResponse> {
                    clock.refresh()
                }
            }
        }

        "refresh throws MalformedResponse when time is not RFC-3339" {
            runTest {
                server.onMethod("status") {
                    rpcSuccess(result = """{"sync_info":{"latest_block_height":"1","latest_block_time":"not-a-date"}}""")
                }
                val clock = CosmWasmBlockClock(client)
                shouldThrow<CosmWasmChainAdapterException.MalformedResponse> {
                    clock.refresh()
                }
            }
        }

        "refresh handles nanosecond-precision RFC-3339 timestamps" {
            runTest {
                server.onMethod("status") {
                    rpcSuccess(
                        result = """{"sync_info":{"latest_block_height":"99","latest_block_time":"2024-01-15T08:30:00.123456789Z"}}""",
                    )
                }
                val clock = CosmWasmBlockClock(client)
                clock.refresh()
                clock.currentBlock() shouldBe 99L
                clock.currentTime().epochSecond shouldBe Instant.parse("2024-01-15T08:30:00Z").epochSecond
            }
        }
    })
