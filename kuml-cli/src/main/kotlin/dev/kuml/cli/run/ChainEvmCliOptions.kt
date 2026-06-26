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
 * @property chainId Optional chain ID hint (informational; EvmChainAdapter auto-detects from RPC).
 */
internal data class ChainEvmCliOptions(
    val rpcUrl: String,
    val contractAddress: String,
    val fromBlock: Long? = null,
    val chainId: Int? = null,
)
