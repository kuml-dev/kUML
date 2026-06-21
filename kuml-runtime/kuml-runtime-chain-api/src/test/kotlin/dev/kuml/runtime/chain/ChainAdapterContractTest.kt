package dev.kuml.runtime.chain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.time.Instant

class ChainAdapterContractTest :
    StringSpec({
        // ── connect ───────────────────────────────────────────────────────────────

        "connect: returns ContractIdentity with expected address and modelHash" {
            runTest {
                val adapter = FakeChainAdapter()
                val identity = adapter.connect("http://localhost:8545", "0xABCDEF")

                identity.address shouldBe "0xABCDEF"
                identity.schemaVersion shouldBe 1
                // modelHash must be 32 bytes (SHA-256)
                identity.modelHash.size shouldBe 32
                // modelHash must match what ModelHasher would produce for the fake model source
                val expectedHash = ModelHasher.hashCanonical(ModelHasher.canonicalize(FakeChainAdapter.MODEL_SOURCE))
                identity.modelHash.contentEquals(expectedHash).shouldBeTrue()
            }
        }

        "connect: ContractIdentity.equals is structural (ByteArray contentEquals)" {
            runTest {
                val adapter = FakeChainAdapter()
                val id1 = adapter.connect("http://localhost:8545", "0xABCDEF")
                val id2 = adapter.connect("http://localhost:8545", "0xABCDEF")
                // Two separate calls should return structurally equal identities
                id1 shouldBe id2
            }
        }

        // ── subscribe ─────────────────────────────────────────────────────────────

        "subscribe: emits a predefined event sequence in order" {
            runTest {
                val adapter = FakeChainAdapter()
                val events = adapter.subscribe().take(3).toList()

                events.size shouldBe 3
                events[0].eventType shouldBe "OrderPlaced"
                events[1].eventType shouldBe "OrderConfirmed"
                events[2].eventType shouldBe "OrderShipped"
            }
        }

        "subscribe: is a cold flow — each collection starts fresh" {
            runTest {
                val adapter = FakeChainAdapter()
                val first = adapter.subscribe().take(2).toList()
                val second = adapter.subscribe().take(2).toList()

                // Cold flow: second collection starts from the beginning
                first[0].eventType shouldBe second[0].eventType
            }
        }

        // ── replay ────────────────────────────────────────────────────────────────

        "replay: includes only events at or after fromBlock" {
            runTest {
                val adapter = FakeChainAdapter()
                val events = adapter.replay(fromBlock = 101L).toList()

                events.isNotEmpty() shouldBe true
                for (e in events) {
                    e.blockNumber shouldBeGreaterThanOrEqualTo 101L
                }
            }
        }

        "replay: terminates (flow completes)" {
            runTest {
                val adapter = FakeChainAdapter()
                // replay(0) must complete and return all fake events
                val events = adapter.replay(fromBlock = 0L).toList()
                events.size shouldBe 3
            }
        }

        // ── blockClock ────────────────────────────────────────────────────────────

        "blockClock: currentBlock returns non-negative value" {
            val adapter = FakeChainAdapter()
            adapter.blockClock().currentBlock() shouldBeGreaterThanOrEqualTo 0L
        }

        "blockClock: currentTime returns a consistent Instant" {
            val adapter = FakeChainAdapter()
            val clock = adapter.blockClock()
            clock.currentTime() shouldNotBe null
            // Verify it's a real Instant (not epoch 0, unless intentionally so in fake)
            clock.currentTime().epochSecond shouldBeGreaterThanOrEqualTo 0L
        }

        // ── ChainEvent structural equality ─────────────────────────────────────────

        "ChainEvent.equals is structural (same payloadAbi content = equal)" {
            val payload = byteArrayOf(1, 2, 3)
            val e1 = ChainEvent("Transfer", payload.copyOf(), 100L, "0xtx1")
            val e2 = ChainEvent("Transfer", payload.copyOf(), 100L, "0xtx1")

            // Must be equal even though payloadAbi is two different ByteArray instances
            e1 shouldBe e2
        }

        "ChainEvent.equals: different payloadAbi content = not equal" {
            val e1 = ChainEvent("Transfer", byteArrayOf(1, 2, 3), 100L, "0xtx1")
            val e2 = ChainEvent("Transfer", byteArrayOf(9, 9, 9), 100L, "0xtx1")
            (e1 == e2) shouldBe false
        }
    })

// ── FakeChainAdapter ──────────────────────────────────────────────────────────

/**
 * In-memory implementation of [KumlChainAdapter] for contract tests.
 * Deterministic, no network required.
 */
private class FakeChainAdapter : KumlChainAdapter {
    companion object {
        /** Source used to compute the fake [ContractIdentity.modelHash]. */
        const val MODEL_SOURCE = "model {\n    state(\"A\")\n    state(\"B\")\n}\n"

        private val FAKE_EVENTS =
            listOf(
                ChainEvent("OrderPlaced", byteArrayOf(0x01), blockNumber = 100L, txHash = "0xtx100"),
                ChainEvent("OrderConfirmed", byteArrayOf(0x02), blockNumber = 101L, txHash = "0xtx101"),
                ChainEvent("OrderShipped", byteArrayOf(0x03), blockNumber = 102L, txHash = "0xtx102"),
            )
    }

    override suspend fun connect(
        rpcUrl: String,
        contractAddress: String,
    ): ContractIdentity =
        ContractIdentity(
            address = contractAddress,
            modelHash = ModelHasher.hashCanonical(ModelHasher.canonicalize(MODEL_SOURCE)),
            modelUri = "ipfs://QmFakeHash123",
            schemaVersion = 1,
        )

    override fun subscribe(): Flow<ChainEvent> =
        flow {
            // Emit endlessly (like a real subscription); tests use take(n) to bound it.
            var i = 0
            while (true) {
                emit(FAKE_EVENTS[i % FAKE_EVENTS.size])
                i++
            }
        }

    override fun blockClock(): BlockClock =
        object : BlockClock {
            override fun currentTime(): Instant = Instant.ofEpochSecond(1_700_000_000L)

            override fun currentBlock(): Long = 200L
        }

    override fun replay(fromBlock: Long): Flow<ChainEvent> =
        flow {
            // Finite: emit only events at or after fromBlock, then complete.
            for (e in FAKE_EVENTS) {
                if (e.blockNumber >= fromBlock) emit(e)
            }
        }
}
