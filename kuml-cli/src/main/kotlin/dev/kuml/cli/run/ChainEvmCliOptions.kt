package dev.kuml.cli.run

/**
 * Value holder for `kuml run --adapter chain-evm` options.
 *
 * Constructed by [RunCommand] after all validations have passed. Passed to
 * [ChainEvmAdapterRunner] which uses these values to connect the [dev.kuml.runtime.chain.evm.EvmChainAdapter].
 *
 * @property rpcUrl Validated EVM JSON-RPC endpoint (http/https, no private ranges).
 * @property contractAddress On-chain contract address (with 0x prefix, normalized by [EvmUrlValidator.normalizeContract]).
 * @property fromBlock If non-null, replay from this block number via [dev.kuml.runtime.chain.KumlChainAdapter.replay].
 *   If null, subscribe to live events via [dev.kuml.runtime.chain.KumlChainAdapter.subscribe].
 * @property chainId Optional chain ID hint. This value is **informational only and is not forwarded
 *   to [dev.kuml.runtime.chain.KumlChainAdapter.connect]**. [dev.kuml.runtime.chain.evm.EvmChainAdapter]
 *   always auto-detects the chain ID from the RPC endpoint at connect time. The field is retained so
 *   callers can surface the user-supplied value in diagnostics or future API extensions.
 */
internal data class ChainEvmCliOptions(
    val rpcUrl: String,
    val contractAddress: String,
    val fromBlock: Long? = null,
    val chainId: Int? = null,
)
