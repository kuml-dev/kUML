package dev.kuml.runtime.chain.cosmos.substrate

import dev.kuml.runtime.chain.cosmos.MockRpcServer
import dev.kuml.runtime.chain.cosmos.rpcSuccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.time.Instant

class SubstrateBlockClockTest :
    StringSpec({
        lateinit var server: MockRpcServer
        lateinit var client: SubstrateRpcClient

        beforeTest {
            server = MockRpcServer()
            server.start()
            client = SubstrateRpcClient(server.baseUrl())
        }

        afterTest { server.stop() }

        "initial values are EPOCH and 0" {
            val clock = SubstrateBlockClock(client)
            clock.currentTime() shouldBe Instant.EPOCH
            clock.currentBlock() shouldBe 0L
        }

        "refresh updates block height and timestamp from Timestamp.Now" {
            runTest {
                server.onMethod("chain_getFinalizedHead") { rpcSuccess(result = "\"0xhash\"") }
                server.onMethod("chain_getHeader") { rpcSuccess(result = """{"number":"0x5"}""") }
                // Timestamp.Now: 1000ms = 0xe8030000_00000000 in LE u64
                server.onMethod("state_getStorage") { rpcSuccess(result = "\"0xe803000000000000\"") }
                val clock = SubstrateBlockClock(client)
                clock.refresh()
                clock.currentBlock() shouldBe 5L
                clock.currentTime() shouldBe Instant.ofEpochMilli(1000L)
            }
        }

        "decodeU64LittleEndian returns 0 for empty hex" {
            SubstrateBlockClock.decodeU64LittleEndian("") shouldBe 0L
            SubstrateBlockClock.decodeU64LittleEndian("0x") shouldBe 0L
        }

        "decodeU64LittleEndian decodes 8-byte LE value correctly" {
            // 1000 ms = 0x3E8 = bytes [E8, 03, 00, 00, 00, 00, 00, 00] in LE
            SubstrateBlockClock.decodeU64LittleEndian("0xe803000000000000") shouldBe 1000L
        }

        "decodeU64LittleEndian tolerates fewer than 8 bytes" {
            // 2 bytes [E8, 03] in LE = 0x03E8 = 1000
            SubstrateBlockClock.decodeU64LittleEndian("0xe803") shouldBe 1000L
        }

        "decodeU64LittleEndian throws MalformedResponse for odd-length hex" {
            shouldThrow<SubstrateChainAdapterException.MalformedResponse> {
                SubstrateBlockClock.decodeU64LittleEndian("0xabc")
            }
        }

        "decodeU64LittleEndian throws MalformedResponse for invalid hex" {
            shouldThrow<SubstrateChainAdapterException.MalformedResponse> {
                SubstrateBlockClock.decodeU64LittleEndian("0xZZZZ")
            }
        }
    })
