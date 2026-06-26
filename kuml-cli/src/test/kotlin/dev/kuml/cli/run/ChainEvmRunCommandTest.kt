package dev.kuml.cli.run

import dev.kuml.cli.ExitCodes
import dev.kuml.runtime.chain.BlockClock
import dev.kuml.runtime.chain.ChainEvent
import dev.kuml.runtime.chain.ContractIdentity
import dev.kuml.runtime.chain.KumlChainAdapter
import dev.kuml.runtime.chain.ModelHasher
import dev.kuml.runtime.chain.evm.EvmChainAdapterException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant

/**
 * Integration tests for [ChainEvmAdapterRunner] using an injectable [FakeChainAdapter].
 * No real network or testnet is used.
 */
class ChainEvmRunCommandTest :
    FunSpec({
        val validRpc = "https://mainnet.example.com/v3/key"
        val validContract = "0xdAC17F958D2ee523a2206206994597C13D831ec7"
        val scriptText = "stateMachine(\"test\") {}"

        fun matchingHash(): ByteArray = ModelHasher.hashCanonical(ModelHasher.canonicalize(scriptText))

        fun makeOptions(
            fromBlock: Long? = null,
            chainId: Int? = null,
        ) = ChainEvmCliOptions(
            rpcUrl = validRpc,
            contractAddress = validContract,
            fromBlock = fromBlock,
            chainId = chainId,
        )

        fun captureStreams(): Pair<ByteArrayOutputStream, ByteArrayOutputStream> = ByteArrayOutputStream() to ByteArrayOutputStream()

        // ── Fake adapter ──────────────────────────────────────────────────────

        class FakeChainAdapter(
            private val modelHash: ByteArray = matchingHash(),
            private val connectThrows: Throwable? = null,
            private val events: List<ChainEvent> = emptyList(),
        ) : KumlChainAdapter {
            var connectCalled = false
            var subscribeCalled = false
            var replayCalled = false
            var replayFromBlock: Long? = null

            override suspend fun connect(
                rpcUrl: String,
                contractAddress: String,
            ): ContractIdentity {
                connectCalled = true
                connectThrows?.let { throw it }
                return ContractIdentity(
                    address = contractAddress,
                    modelHash = modelHash,
                    modelUri = "ipfs://fake",
                    schemaVersion = 1,
                )
            }

            override fun subscribe(): Flow<ChainEvent> {
                subscribeCalled = true
                return if (events.isEmpty()) emptyFlow() else flowOf(*events.toTypedArray())
            }

            override fun blockClock(): BlockClock =
                object : BlockClock {
                    override fun currentTime(): Instant = Instant.EPOCH

                    override fun currentBlock(): Long = 0L
                }

            override fun replay(fromBlock: Long): Flow<ChainEvent> {
                replayCalled = true
                replayFromBlock = fromBlock
                return if (events.isEmpty()) emptyFlow() else flowOf(*events.toTypedArray())
            }
        }

        // ── Tests ─────────────────────────────────────────────────────────────

        test("valid rpc connects and consumes events via subscribe") {
            val events =
                listOf(
                    ChainEvent("OrderPlaced", ByteArray(0), 100L, "0xabc"),
                    ChainEvent("OrderShipped", ByteArray(0), 101L, "0xdef"),
                )
            val fake = FakeChainAdapter(events = events)
            val manager = RunSessionManager()
            val (outBuf, errBuf) = captureStreams()

            val result =
                ChainEvmAdapterRunner(
                    manager = manager,
                    options = makeOptions(),
                    scriptText = scriptText,
                    adapterFactory = { fake },
                    output = PrintStream(outBuf),
                    errOut = PrintStream(errBuf),
                ).run()

            fake.connectCalled.shouldBeTrue()
            fake.subscribeCalled.shouldBeTrue()
            fake.replayCalled.shouldBeFalse()
            result shouldBe 0
        }

        test("fromBlock provided uses replay") {
            val fake = FakeChainAdapter()
            val manager = RunSessionManager()
            val (outBuf, errBuf) = captureStreams()

            ChainEvmAdapterRunner(
                manager = manager,
                options = makeOptions(fromBlock = 500L),
                scriptText = scriptText,
                adapterFactory = { fake },
                output = PrintStream(outBuf),
                errOut = PrintStream(errBuf),
            ).run()

            fake.replayCalled.shouldBeTrue()
            fake.replayFromBlock shouldBe 500L
            fake.subscribeCalled.shouldBeFalse()
        }

        test("fromBlock absent uses subscribe") {
            val fake = FakeChainAdapter()
            val manager = RunSessionManager()
            val (outBuf, errBuf) = captureStreams()

            ChainEvmAdapterRunner(
                manager = manager,
                options = makeOptions(fromBlock = null),
                scriptText = scriptText,
                adapterFactory = { fake },
                output = PrintStream(outBuf),
                errOut = PrintStream(errBuf),
            ).run()

            fake.subscribeCalled.shouldBeTrue()
            fake.replayCalled.shouldBeFalse()
        }

        test("hash mismatch exits with CHAIN_HASH_MISMATCH") {
            val wrongHash = ByteArray(32) { 0xFF.toByte() }
            val fake = FakeChainAdapter(modelHash = wrongHash)
            val manager = RunSessionManager()
            val (outBuf, errBuf) = captureStreams()

            val result =
                ChainEvmAdapterRunner(
                    manager = manager,
                    options = makeOptions(),
                    scriptText = scriptText,
                    adapterFactory = { fake },
                    output = PrintStream(outBuf),
                    errOut = PrintStream(errBuf),
                ).run()

            result shouldBe ExitCodes.CHAIN_HASH_MISMATCH
            errBuf.toString() shouldContain "modelHash"
            fake.connectCalled.shouldBeTrue()
            fake.subscribeCalled.shouldBeFalse()
        }

        test("connection failure produces friendly error without stacktrace") {
            val fake =
                FakeChainAdapter(
                    connectThrows = EvmChainAdapterException.NetworkError("Connection refused"),
                )
            val manager = RunSessionManager()
            val (outBuf, errBuf) = captureStreams()

            val result =
                ChainEvmAdapterRunner(
                    manager = manager,
                    options = makeOptions(),
                    scriptText = scriptText,
                    adapterFactory = { fake },
                    output = PrintStream(outBuf),
                    errOut = PrintStream(errBuf),
                ).run()

            result shouldBe ExitCodes.CHAIN_CONNECT_ERROR
            errBuf.toString() shouldContain "connection failed"
            errBuf.toString() shouldNotContain "at dev.kuml"
        }

        test("private IP is rejected by EvmUrlValidator before runner is invoked") {
            EvmUrlValidator
                .validateRpcUrl("http://10.0.0.1:8545")
                .shouldBeInstanceOf<EvmUrlValidator.Result.Invalid>()
        }

        test("invalid contract address is rejected by EvmUrlValidator") {
            EvmUrlValidator
                .validateContractAddress("0x123")
                .shouldBeInstanceOf<EvmUrlValidator.Result.Invalid>()
        }
    })
