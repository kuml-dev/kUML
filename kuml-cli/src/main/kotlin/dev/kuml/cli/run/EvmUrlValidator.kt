package dev.kuml.cli.run

import dev.kuml.runtime.chain.evm.RpcUrlValidator

/**
 * Validates EVM RPC URLs and contract addresses for the `kuml run --adapter chain-evm` command.
 *
 * RPC URL validation delegates to [RpcUrlValidator.Default] which enforces the full SSRF guard
 * (http/https only, no private IP ranges). Contract address validation checks the 40-hex format
 * (with optional 0x prefix). Use [normalizeContract] to ensure the 0x prefix required by
 * [dev.kuml.runtime.chain.evm.EvmChainAdapter.connect].
 */
internal object EvmUrlValidator {
    /**
     * Result of a validation check.
     */
    internal sealed class Result {
        internal object Valid : Result()

        internal data class Invalid(
            val message: String,
        ) : Result()
    }

    private val CONTRACT_ADDRESS_REGEX = Regex("^(0x)?[0-9a-fA-F]{40}$")

    /**
     * Validates [rpcUrl] against SSRF rules by delegating to [RpcUrlValidator.Default].
     *
     * Accepts http and https only. Rejects file://, ftp://, and other schemes.
     * Rejects IP literals in private ranges (10.x, 172.16-31.x, 192.168.x, 127.x, ::1).
     * Hostnames are NOT checked for DNS rebinding (out of scope per EvmChainAdapter docs).
     */
    internal fun validateRpcUrl(rpcUrl: String): Result =
        try {
            RpcUrlValidator.Default.validate(rpcUrl)
            Result.Valid
        } catch (e: IllegalArgumentException) {
            Result.Invalid(e.message ?: "Invalid RPC URL")
        }

    /**
     * Validates [address] as a 40-hex Ethereum address (0x prefix optional).
     */
    internal fun validateContractAddress(address: String): Result =
        if (CONTRACT_ADDRESS_REGEX.matches(address)) {
            Result.Valid
        } else {
            Result.Invalid(
                "contractAddress must be 40 hex characters (with optional 0x prefix), was: '$address'",
            )
        }

    /**
     * Prepends '0x' to [address] if not already present.
     *
     * [dev.kuml.runtime.chain.evm.EvmChainAdapter.connect] requires the 0x prefix but the
     * CLI spec allows an optional prefix, so normalize before calling connect().
     */
    internal fun normalizeContract(address: String): String =
        if (address.startsWith("0x") || address.startsWith("0X")) address else "0x$address"
}
