package dev.kuml.runtime.chain.multi

import dev.kuml.runtime.chain.BlockClock
import dev.kuml.runtime.chain.ChainEvent
import dev.kuml.runtime.chain.ContractIdentity
import dev.kuml.runtime.chain.KumlChainAdapter
import dev.kuml.runtime.chain.ModelHasher
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.time.Instant

class MultiChainAdapterTest :
    StringSpec({

        // ── connectAll ────────────────────────────────────────────────────────────

        "connectAll: verbindet 2 Adapter parallel und liefert beide chainIds in identities" {
            runTest {
                val adapter =
                    MultiChainAdapter(
                        linkedMapOf(
                            "evm" to FakeChainAdapter(modelSource = MODEL_A),
                            "sui" to FakeChainAdapter(modelSource = MODEL_A),
                        ),
                    )
                val identity =
                    adapter.connectAll(
                        linkedMapOf(
                            "evm" to ("http://evm-rpc" to "0xEVM"),
                            "sui" to ("http://sui-rpc" to "0xSUI"),
                        ),
                    )
                identity.identities.keys shouldContainAll listOf("evm", "sui")
                identity.identities.size shouldBe 2
            }
        }

        "connectAll: gleiche modelHash auf allen Chains → consistent=true" {
            runTest {
                val adapter =
                    MultiChainAdapter(
                        linkedMapOf(
                            "evm" to FakeChainAdapter(modelSource = MODEL_A),
                            "sui" to FakeChainAdapter(modelSource = MODEL_A),
                        ),
                    )
                val identity =
                    adapter.connectAll(
                        linkedMapOf(
                            "evm" to ("http://evm-rpc" to "0xEVM"),
                            "sui" to ("http://sui-rpc" to "0xSUI"),
                        ),
                    )
                identity.consistent.shouldBeTrue()
            }
        }

        "connectAll: unterschiedliche modelHash → consistent=false" {
            runTest {
                val adapter =
                    MultiChainAdapter(
                        linkedMapOf(
                            "evm" to FakeChainAdapter(modelSource = MODEL_A),
                            "sui" to FakeChainAdapter(modelSource = MODEL_B),
                        ),
                    )
                val identity =
                    adapter.connectAll(
                        linkedMapOf(
                            "evm" to ("http://evm-rpc" to "0xEVM"),
                            "sui" to ("http://sui-rpc" to "0xSUI"),
                        ),
                    )
                identity.consistent.shouldBeFalse()
            }
        }

        "connectAll: endpoint-Keys sind Teilmenge der adapter-Keys → IllegalArgumentException" {
            runTest {
                val adapter =
                    MultiChainAdapter(
                        linkedMapOf(
                            "evm" to FakeChainAdapter(modelSource = MODEL_A),
                            "sui" to FakeChainAdapter(modelSource = MODEL_A),
                        ),
                    )
                io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                    adapter.connectAll(
                        linkedMapOf("evm" to ("http://evm-rpc" to "0xEVM")), // "sui" fehlt
                    )
                }
            }
        }

        "connectAll: endpoint-Keys sind Obermenge der adapter-Keys → IllegalArgumentException" {
            runTest {
                val adapter =
                    MultiChainAdapter(
                        linkedMapOf(
                            "evm" to FakeChainAdapter(modelSource = MODEL_A),
                            "sui" to FakeChainAdapter(modelSource = MODEL_A),
                        ),
                    )
                io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                    adapter.connectAll(
                        linkedMapOf(
                            "evm" to ("http://evm-rpc" to "0xEVM"),
                            "sui" to ("http://sui-rpc" to "0xSUI"),
                            "aptos" to ("http://aptos-rpc" to "0xAPTOS"), // überschüssig
                        ),
                    )
                }
            }
        }

        // ── subscribeAll ──────────────────────────────────────────────────────────

        "subscribeAll: 2 Chains, take(4) liefert 4 Events" {
            runTest {
                // Unterschiedliche eventTypes → kein Konflikt → alle Events emittiert
                val adapter =
                    MultiChainAdapter(
                        linkedMapOf(
                            "evm" to
                                FakeChainAdapter(
                                    events =
                                        listOf(
                                            ChainEvent("EvmEvt", byteArrayOf(0x01), 100L, "0xevm-tx1"),
                                            ChainEvent("EvmEvt", byteArrayOf(0x02), 101L, "0xevm-tx2"),
                                        ),
                                ),
                            "sui" to
                                FakeChainAdapter(
                                    events =
                                        listOf(
                                            ChainEvent("SuiEvt", byteArrayOf(0x03), 100L, "0xsui-tx1"),
                                            ChainEvent("SuiEvt", byteArrayOf(0x04), 101L, "0xsui-tx2"),
                                        ),
                                ),
                        ),
                    )
                val events = adapter.subscribeAll().take(4).toList()
                events.size shouldBe 4
            }
        }

        "subscribeAll: globalSequence der genommenen Events ist streng monoton steigend" {
            runTest {
                // Unterschiedliche eventTypes → kein Konflikt → alle Events emittiert
                val adapter =
                    MultiChainAdapter(
                        linkedMapOf(
                            "evm" to
                                FakeChainAdapter(
                                    events =
                                        listOf(
                                            ChainEvent("EvmEvt", byteArrayOf(0x01), 100L, "0xevm-tx1"),
                                            ChainEvent("EvmEvt", byteArrayOf(0x02), 101L, "0xevm-tx2"),
                                            ChainEvent("EvmEvt", byteArrayOf(0x03), 102L, "0xevm-tx3"),
                                        ),
                                ),
                            "sui" to
                                FakeChainAdapter(
                                    events =
                                        listOf(
                                            ChainEvent("SuiEvt", byteArrayOf(0x04), 100L, "0xsui-tx1"),
                                            ChainEvent("SuiEvt", byteArrayOf(0x05), 101L, "0xsui-tx2"),
                                            ChainEvent("SuiEvt", byteArrayOf(0x06), 102L, "0xsui-tx3"),
                                        ),
                                ),
                        ),
                    )
                val events = adapter.subscribeAll().take(6).toList()
                val monoton =
                    events.zipWithNext { a, b -> a.globalSequence < b.globalSequence }.all { it }
                monoton.shouldBeTrue()
            }
        }

        "subscribeAll: beide chainIds sind im gemergten Stream vertreten" {
            runTest {
                // Finite subscribe-Flows (je 1 Event) → Flow.merge terminiert nach 2 Events.
                // Dadurch ist garantiert, dass Events beider Chains im Ergebnis landen,
                // unabhängig vom Scheduling-Verhalten des TestDispatchers.
                val adapter =
                    MultiChainAdapter(
                        linkedMapOf(
                            "evm" to
                                FakeChainAdapterFinite(
                                    listOf(ChainEvent("EvmEvt", byteArrayOf(0x01), 100L, "0xevm-tx1")),
                                ),
                            "sui" to
                                FakeChainAdapterFinite(
                                    listOf(ChainEvent("SuiEvt", byteArrayOf(0x02), 101L, "0xsui-tx1")),
                                ),
                        ),
                    )
                val events = adapter.subscribeAll().toList()
                val chainIds = events.map { it.chainId }.toSet()
                chainIds shouldContainAll listOf("evm", "sui")
            }
        }

        // ── subscribeAll: Konflikt-Erkennung ──────────────────────────────────────

        "subscribeAll Konflikt: gleicher eventType, |blockDiff|<=2, verschiedene Chains → LOSER+WINNER emittiert" {
            runTest {
                // EVM sendet Block 100, SUI sendet Block 99 — gleicher eventType, |diff|=1 ≤ 2.
                // EarliestBlock wählt SUI (Block 99 < 100) als Gewinner.
                //
                // Beobachtungsreihenfolge (TestDispatcher: EVM zuerst):
                //   emit NONE(evm@100)  ← vor Konflikt-Erkennung
                //   detect conflict: EVM verliert gegen SUI
                //   emit LOSER(evm@100) ← Tombstone für das bereits emittierte EVM-Event
                //   emit WINNER(sui@99)
                // Gesamt: 3 Events. Das originale NONE(evm) ist unvermeidlich (push-only Flow).
                //
                // Umgekehrte Reihenfolge (SUI zuerst): SUI wird als NONE emittiert,
                // EVM kommt danach, verliert still (candidate verworfen) → nur 1 Event: NONE(sui).
                // Wir testen nur den EVM-zuerst-Pfad, da mit TestDispatcher EVM immer zuerst
                // kommt (LinkedHashMap-Reihenfolge bestimmt Merge-Startreihenfolge).
                val adapter =
                    MultiChainAdapter(
                        linkedMapOf(
                            "evm" to
                                FakeChainAdapterFinite(
                                    listOf(ChainEvent("Transfer", byteArrayOf(0x01), 100L, "0xevm-tx")),
                                ),
                            "sui" to
                                FakeChainAdapterFinite(
                                    listOf(ChainEvent("Transfer", byteArrayOf(0x02), 99L, "0xsui-tx")),
                                ),
                        ),
                        conflictResolver = ConflictResolver.EarliestBlock,
                    )
                val events = adapter.subscribeAll().toList()
                // Konflikt muss erkannt worden sein: mindestens ein LOSER-Tombstone oder
                // mindestens ein WINNER im Stream.
                val hasConflict = events.any { it.conflictRole == ConflictRole.LOSER || it.conflictRole == ConflictRole.WINNER }
                hasConflict.shouldBeTrue()
                // EVM darf nie als WINNER erscheinen — SUI@99 hat niedrigere Blocknummer
                events.none { it.chainId == "evm" && it.conflictRole == ConflictRole.WINNER }.shouldBeTrue()
                // SUI darf nie als LOSER erscheinen
                events.none { it.chainId == "sui" && it.conflictRole == ConflictRole.LOSER }.shouldBeTrue()
            }
        }

        "subscribeAll Konflikt: gleicher eventType, |blockDiff|>2 → beide Events mit NONE emittiert (kein Konflikt)" {
            runTest {
                // |100 - 95| = 5 > CONFLICT_WINDOW_BLOCKS=2 → kein Konflikt
                val adapter =
                    MultiChainAdapter(
                        linkedMapOf(
                            "evm" to
                                FakeChainAdapterFinite(
                                    listOf(ChainEvent("Transfer", byteArrayOf(0x01), 100L, "0xevm-tx")),
                                ),
                            "sui" to
                                FakeChainAdapterFinite(
                                    listOf(ChainEvent("Transfer", byteArrayOf(0x02), 95L, "0xsui-tx")),
                                ),
                        ),
                    )
                val events = adapter.subscribeAll().toList()
                events.size shouldBe 2
                events.all { it.conflictRole == ConflictRole.NONE }.shouldBeTrue()
            }
        }

        "subscribeAll Konflikt PriorityChain: priorisierte Chain gewinnt, andere wird als LOSER markiert" {
            runTest {
                // EVM hat Priorität. SUI kommt mit gleichem eventType, gleicher blockNumber.
                // PriorityChain → EVM gewinnt, SUI verliert.
                // Da subscribe() nicht-deterministisch interleaved, nutzen wir FakeChainAdapterFinite
                // mit je 1 Event und prüfen die Rollen unabhängig von der Reihenfolge.
                val adapter =
                    MultiChainAdapter(
                        linkedMapOf(
                            "evm" to
                                FakeChainAdapterFinite(
                                    listOf(ChainEvent("Transfer", byteArrayOf(0x01), 100L, "0xevm-tx")),
                                ),
                            "sui" to
                                FakeChainAdapterFinite(
                                    listOf(ChainEvent("Transfer", byteArrayOf(0x02), 100L, "0xsui-tx")),
                                ),
                        ),
                        conflictResolver = ConflictResolver.PriorityChain(listOf("evm", "sui")),
                    )
                val events = adapter.subscribeAll().toList()
                // Eines der beiden Events muss WINNER sein, keines darf beide NONE sein
                val roles = events.map { it.conflictRole }.toSet()
                // Im Konfliktfall: entweder LOSER+WINNER (wenn das erste emittierte Event verliert)
                // oder nur NONE für EVM (wenn EVM zuerst kommt und gewinnt, SUI wird still verworfen)
                val hasConflictResolution =
                    roles.contains(ConflictRole.WINNER) || events.none { it.chainId == "sui" }
                hasConflictResolution.shouldBeTrue()
                // EVM darf nie LOSER sein, weil EVM höchste Priorität hat
                events.none { it.chainId == "evm" && it.conflictRole == ConflictRole.LOSER }.shouldBeTrue()
            }
        }

        "subscribeAll Konflikt EarliestBlock: exakte Blockdifferenz 2 gilt als Konflikt, Verlierer wird still verworfen" {
            runTest {
                // |blockDiff| = 2 = CONFLICT_WINDOW_BLOCKS → Grenzfall: Konflikt muss erkannt werden.
                // EVM@100 kommt zuerst (LinkedHashMap-Reihenfolge) → NONE emittiert, in recent.
                // SUI@102 kommt danach → Konflikt erkannt (|100-102|=2≤2).
                // EarliestBlock: EVM@100 < SUI@102 → EVM gewinnt = rival === winner
                // → candidate (SUI) wird STILL VERWORFEN, kein LOSER/WINNER emittiert.
                // Ergebnis: genau 1 Event im Stream — NONE(evm@100). SUI erscheint gar nicht.
                val adapter =
                    MultiChainAdapter(
                        linkedMapOf(
                            "evm" to
                                FakeChainAdapterFinite(
                                    listOf(ChainEvent("Transfer", byteArrayOf(0x01), 100L, "0xevm-tx")),
                                ),
                            "sui" to
                                FakeChainAdapterFinite(
                                    listOf(ChainEvent("Transfer", byteArrayOf(0x02), 102L, "0xsui-tx")),
                                ),
                        ),
                        conflictResolver = ConflictResolver.EarliestBlock,
                    )
                val events = adapter.subscribeAll().toList()
                // EVM gewinnt still: nur 1 Event mit NONE(evm), SUI taucht gar nicht auf
                events.size shouldBe 1
                events.first().chainId shouldBe "evm"
                events.first().conflictRole shouldBe ConflictRole.NONE
            }
        }

        // ── replayAll ─────────────────────────────────────────────────────────────

        "replayAll: Events sind aufsteigend nach blockNumber sortiert" {
            runTest {
                val adapter =
                    MultiChainAdapter(
                        linkedMapOf(
                            "evm" to
                                FakeChainAdapter(
                                    events =
                                        listOf(
                                            ChainEvent("Transfer", byteArrayOf(1), 100L, "0xtx-evm-100"),
                                            ChainEvent("Transfer", byteArrayOf(2), 102L, "0xtx-evm-102"),
                                        ),
                                ),
                            "sui" to
                                FakeChainAdapter(
                                    events =
                                        listOf(
                                            ChainEvent("Transfer", byteArrayOf(3), 101L, "0xtx-sui-101"),
                                            ChainEvent("Transfer", byteArrayOf(4), 103L, "0xtx-sui-103"),
                                        ),
                                ),
                        ),
                    )
                val events = adapter.replayAll(fromBlock = 0L).toList()
                val sorted =
                    events.zipWithNext { a, b -> a.blockNumber <= b.blockNumber }.all { it }
                sorted.shouldBeTrue()
            }
        }

        "replayAll: terminiert und enthält Events beider Chains" {
            runTest {
                val eventsEvm =
                    listOf(
                        ChainEvent("E1", byteArrayOf(1), 100L, "0xevm-tx1"),
                        ChainEvent("E2", byteArrayOf(2), 101L, "0xevm-tx2"),
                    )
                val eventsSui =
                    listOf(
                        ChainEvent("S1", byteArrayOf(3), 100L, "0xsui-tx1"),
                    )
                val adapter =
                    MultiChainAdapter(
                        linkedMapOf(
                            "evm" to FakeChainAdapter(events = eventsEvm),
                            "sui" to FakeChainAdapter(events = eventsSui),
                        ),
                    )
                val result = adapter.replayAll(fromBlock = 0L).toList()
                result.size shouldBe eventsEvm.size + eventsSui.size
            }
        }

        "replayAll: bei Block-Gleichstand gewinnt lexikografisch kleinere chainId deterministisch" {
            runTest {
                val sharedBlock = 100L
                val adapter =
                    MultiChainAdapter(
                        linkedMapOf(
                            "evm" to
                                FakeChainAdapter(
                                    events =
                                        listOf(
                                            ChainEvent("Evt", byteArrayOf(1), sharedBlock, "0xtx-evm"),
                                        ),
                                ),
                            "aptos" to
                                FakeChainAdapter(
                                    events =
                                        listOf(
                                            ChainEvent("Evt", byteArrayOf(2), sharedBlock, "0xtx-aptos"),
                                        ),
                                ),
                        ),
                    )
                // "aptos" < "evm" lexikografisch → aptos-Event kommt zuerst
                val result1 = adapter.replayAll(fromBlock = 0L).toList()
                val result2 = adapter.replayAll(fromBlock = 0L).toList()
                result1.map { it.chainId } shouldBe result2.map { it.chainId }
                result1.first().chainId shouldBe "aptos"
            }
        }

        "replayAll: maxEvents-Limit überschritten → IllegalStateException mit klarer Meldung" {
            runTest {
                // 3 Events total (2 evm + 1 sui), Limit auf 2 → Exception beim 3. Event
                val adapter =
                    MultiChainAdapter(
                        linkedMapOf(
                            "evm" to
                                FakeChainAdapter(
                                    events =
                                        listOf(
                                            ChainEvent("E1", byteArrayOf(1), 100L, "0xevm-tx1"),
                                            ChainEvent("E2", byteArrayOf(2), 101L, "0xevm-tx2"),
                                        ),
                                ),
                            "sui" to
                                FakeChainAdapter(
                                    events =
                                        listOf(
                                            ChainEvent("S1", byteArrayOf(3), 102L, "0xsui-tx1"),
                                        ),
                                ),
                        ),
                    )
                io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
                    adapter.replayAll(fromBlock = 0L, maxEvents = 2L).toList()
                }
            }
        }

        "replayAll: maxEvents-Limit genau erreicht → kein Fehler" {
            runTest {
                // Exakt 2 Events, Limit auf 2 → kein Fehler
                val adapter =
                    MultiChainAdapter(
                        linkedMapOf(
                            "evm" to
                                FakeChainAdapter(
                                    events =
                                        listOf(
                                            ChainEvent("E1", byteArrayOf(1), 100L, "0xevm-tx1"),
                                        ),
                                ),
                            "sui" to
                                FakeChainAdapter(
                                    events =
                                        listOf(
                                            ChainEvent("S1", byteArrayOf(2), 101L, "0xsui-tx1"),
                                        ),
                                ),
                        ),
                    )
                val result = adapter.replayAll(fromBlock = 0L, maxEvents = 2L).toList()
                result.size shouldBe 2
            }
        }
    })

// ── Test-Hilfsdaten ─────────────────────────────────────────────────────────────

private const val MODEL_A = "model {\n    state(\"A\")\n    state(\"B\")\n}\n"
private const val MODEL_B = "model {\n    state(\"X\")\n    state(\"Y\")\n}\n"

private val DEFAULT_EVENTS =
    listOf(
        ChainEvent("OrderPlaced", byteArrayOf(0x01), blockNumber = 100L, txHash = "0xtx100"),
        ChainEvent("OrderConfirmed", byteArrayOf(0x02), blockNumber = 101L, txHash = "0xtx101"),
        ChainEvent("OrderShipped", byteArrayOf(0x03), blockNumber = 102L, txHash = "0xtx102"),
    )

// ── FakeChainAdapter (test-lokal, parametrisierbar) ────────────────────────────

private class FakeChainAdapter(
    private val events: List<ChainEvent> = DEFAULT_EVENTS,
    private val modelSource: String = MODEL_A,
    private val clockBlock: Long = 200L,
) : KumlChainAdapter {
    override suspend fun connect(
        rpcUrl: String,
        contractAddress: String,
    ): ContractIdentity =
        ContractIdentity(
            address = contractAddress,
            modelHash = ModelHasher.hashCanonical(ModelHasher.canonicalize(modelSource)),
            modelUri = "ipfs://QmFakeHash123",
            schemaVersion = 1,
        )

    override fun subscribe(): Flow<ChainEvent> =
        flow {
            var i = 0
            while (true) {
                emit(events[i % events.size])
                i++
            }
        }

    override fun blockClock(): BlockClock =
        object : BlockClock {
            override fun currentTime(): Instant = Instant.ofEpochSecond(1_700_000_000L)

            override fun currentBlock(): Long = clockBlock
        }

    override fun replay(fromBlock: Long): Flow<ChainEvent> =
        flow {
            for (e in events) {
                if (e.blockNumber >= fromBlock) emit(e)
            }
        }
}

/**
 * Wie [FakeChainAdapter], aber subscribe() terminiert nach einmaligem Durchlauf der Events.
 * Nützlich für Tests, die sicherstellen wollen, dass Events BEIDER Chains im Merge landen,
 * ohne vom Scheduling des TestDispatchers abzuhängen.
 */
private class FakeChainAdapterFinite(
    private val events: List<ChainEvent>,
    private val modelSource: String = MODEL_A,
    private val clockBlock: Long = 200L,
) : KumlChainAdapter {
    override suspend fun connect(
        rpcUrl: String,
        contractAddress: String,
    ): ContractIdentity =
        ContractIdentity(
            address = contractAddress,
            modelHash = ModelHasher.hashCanonical(ModelHasher.canonicalize(modelSource)),
            modelUri = "ipfs://QmFakeHash123",
            schemaVersion = 1,
        )

    override fun subscribe(): Flow<ChainEvent> =
        flow {
            for (e in events) emit(e) // endlich — terminiert nach allen Events
        }

    override fun blockClock(): BlockClock =
        object : BlockClock {
            override fun currentTime(): Instant = Instant.ofEpochSecond(1_700_000_000L)

            override fun currentBlock(): Long = clockBlock
        }

    override fun replay(fromBlock: Long): Flow<ChainEvent> =
        flow {
            for (e in events) {
                if (e.blockNumber >= fromBlock) emit(e)
            }
        }
}
