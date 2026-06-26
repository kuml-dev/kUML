package dev.kuml.cli.run

import dev.kuml.cli.ExitCodes
import dev.kuml.runtime.chain.KumlChainAdapter
import dev.kuml.runtime.chain.ModelHasher
import dev.kuml.runtime.chain.evm.EvmChainAdapter
import dev.kuml.runtime.chain.evm.EvmChainAdapterException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.PrintStream

/**
 * Bridges `kuml run --adapter chain-evm` to [KumlChainAdapter].
 *
 * Flow:
 * 1. Connect the adapter to [options.rpcUrl] + [options.contractAddress].
 * 2. Verify that the on-chain modelHash matches the local script's SHA-256 hash.
 *    Mismatch → stderr + exit [ExitCodes.CHAIN_HASH_MISMATCH].
 * 3. Start event feed: if [ChainEvmCliOptions.fromBlock] is set use [KumlChainAdapter.replay];
 *    otherwise use [KumlChainAdapter.subscribe] (infinite Cold Flow).
 * 4. Feed each [dev.kuml.runtime.chain.ChainEvent] into [manager] via [RunSessionManager.event].
 *    Stop on [SessionResult.Terminated] or [EvmChainAdapterException.ReorgDetected].
 *
 * Designed for testability: [adapterFactory] and [output]/[errOut] are injectable.
 *
 * @param manager Already-started run session (start() called by RunCommand before dispatching here).
 * @param options Validated CLI options.
 * @param scriptText Raw script text used for local hash computation.
 * @param adapterFactory Factory for [KumlChainAdapter] (default: [EvmChainAdapter]).
 * @param output Standard output stream (default: [System.out]).
 * @param errOut Standard error stream (default: [System.err]).
 */
internal class ChainEvmAdapterRunner(
    private val manager: RunSessionManager,
    private val options: ChainEvmCliOptions,
    private val scriptText: String,
    private val adapterFactory: () -> KumlChainAdapter = { EvmChainAdapter() },
    private val output: PrintStream = System.out,
    private val errOut: PrintStream = System.err,
) {
    /**
     * Runs the chain-evm adapter loop.
     *
     * @return exit code: 0 on clean termination, [ExitCodes.CHAIN_HASH_MISMATCH] on hash mismatch,
     *   [ExitCodes.CHAIN_CONNECT_ERROR] on connection or reorg failure.
     */
    fun run(): Int {
        val adapter = adapterFactory()

        // Step 1: Connect
        val identity =
            try {
                runBlocking { adapter.connect(options.rpcUrl, options.contractAddress) }
            } catch (e: IllegalArgumentException) {
                errOut.println("chain-evm: invalid argument — ${e.message}")
                return ExitCodes.CHAIN_CONNECT_ERROR
            } catch (e: EvmChainAdapterException) {
                errOut.println("chain-evm: connection failed — ${e.message}")
                return ExitCodes.CHAIN_CONNECT_ERROR
            }

        // Step 2: Verify model hash
        val localHash = ModelHasher.hashCanonical(ModelHasher.canonicalize(scriptText))
        if (!localHash.contentEquals(identity.modelHash)) {
            errOut.println(
                "chain-evm: on-chain modelHash does not match local model " +
                    "(contract=${options.contractAddress}). " +
                    "Use 'kuml chain verify' for details.",
            )
            // CHAIN_HASH_MISMATCH (50) is the established code for hash-mismatch in this codebase.
            // The spec refers to ExitCodes.VALIDATION_ERROR which does not exist — map to CHAIN_HASH_MISMATCH.
            return ExitCodes.CHAIN_HASH_MISMATCH
        }

        // Step 3 + 4: Event feed loop
        val flow =
            if (options.fromBlock != null) {
                adapter.replay(options.fromBlock)
            } else {
                adapter.subscribe()
            }

        return try {
            runBlocking {
                var exitCode = 0
                coroutineScope {
                    val job =
                        launch {
                            try {
                                flow
                                    .takeWhile { !manager.isTerminated }
                                    .onEach { ev ->
                                        val payload =
                                            mapOf(
                                                "txHash" to ev.txHash,
                                                "blockNumber" to ev.blockNumber,
                                            )
                                        val result = manager.event(ev.eventType, payload)
                                        when (result) {
                                            is SessionResult.Ok ->
                                                output.println(
                                                    "[chain-evm] ${ev.eventType} @ block ${ev.blockNumber}: " +
                                                        result.message,
                                                )

                                            is SessionResult.Terminated -> {
                                                output.println("[chain-evm] session terminated: ${result.message}")
                                            }

                                            is SessionResult.Error ->
                                                output.println("[chain-evm] error: ${result.message}")
                                        }
                                    }.collect()
                            } catch (e: EvmChainAdapterException.ReorgDetected) {
                                errOut.println(
                                    "chain-evm: chain reorg detected at block ${e.reorgFromBlock} — aborting. " +
                                        "Re-run with --from-block ${e.reorgFromBlock} to replay from the reorg point.",
                                )
                                exitCode = ExitCodes.CHAIN_CONNECT_ERROR
                            }
                        }
                    job.join()
                }
                exitCode
            }
        } catch (_: CancellationException) {
            0
        } catch (e: EvmChainAdapterException) {
            errOut.println("chain-evm: adapter error — ${e.message}")
            ExitCodes.CHAIN_CONNECT_ERROR
        }
    }
}
