package dev.kuml.cli.run

import java.net.InetAddress
import java.net.URI

/**
 * Validates EVM RPC URLs and contract addresses for the `kuml run --adapter chain-evm` command.
 *
 * RPC URL validation enforces the full SSRF guard inline (http/https only, no private IP
 * ranges) without importing from `dev.kuml.runtime.chain.evm`. This is intentional: the
 * `kuml-runtime-chain-evm` module is JVM-only (web3j dependency) and must not be referenced
 * via direct static imports in any class that is compiled into the Native Image CLI binary.
 * All references to `dev.kuml.runtime.chain.evm.*` in the CLI are guarded by
 * `Class.forName(...)` in [dev.kuml.cli.run.RunCommand] so the native image build can
 * succeed without web3j on the classpath.
 *
 * Contract address validation checks the 40-hex format (with optional 0x prefix). Use
 * [normalizeContract] to ensure the 0x prefix required by the EVM adapter's `connect()`.
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

    private val ALLOWED_RPC_SCHEMES = setOf("http", "https")

    /**
     * Validates [rpcUrl] against SSRF rules.
     *
     * Accepts http and https only. Rejects file://, ftp://, and other schemes.
     * Rejects IP literals in private ranges (10.x, 172.16-31.x, 192.168.x, 127.x, ::1).
     * Hostnames are NOT checked for DNS rebinding (out of scope, same as EvmChainAdapter).
     *
     * The validation logic mirrors [dev.kuml.runtime.chain.evm.EvmChainAdapter.validateRpcUrl]
     * exactly. Both must be kept in sync if the SSRF rules change.
     */
    internal fun validateRpcUrl(rpcUrl: String): Result =
        try {
            validateRpcUrlOrThrow(rpcUrl)
            Result.Valid
        } catch (e: IllegalArgumentException) {
            Result.Invalid(e.message ?: "Invalid RPC URL")
        }

    private fun validateRpcUrlOrThrow(rpcUrl: String) {
        val uri =
            try {
                URI(rpcUrl)
            } catch (e: Exception) {
                throw IllegalArgumentException("rpcUrl is not a valid URI: '$rpcUrl'", e)
            }
        require(uri.scheme in ALLOWED_RPC_SCHEMES) {
            "rpcUrl must use http or https, got '${uri.scheme}'"
        }
        val host = uri.host ?: throw IllegalArgumentException("rpcUrl has no host: '$rpcUrl'")
        // Only check IP literals directly (no DNS lookup for hostnames — same policy as EvmChainAdapter)
        if (looksLikeIpLiteral(host)) {
            val addr =
                try {
                    InetAddress.getByName(host)
                } catch (_: Exception) {
                    throw IllegalArgumentException("rpcUrl host is not resolvable as IP: '$host'")
                }
            require(!isPrivateOrLoopback(addr)) {
                "rpcUrl must not address private/loopback/link-local IP ranges (SSRF protection)"
            }
        }
    }

    private fun looksLikeIpLiteral(host: String): Boolean {
        if (host.startsWith("[") || host.contains(":")) return true
        return host.all { it.isDigit() || it == '.' }
    }

    private fun isPrivateOrLoopback(addr: InetAddress): Boolean =
        addr.isLoopbackAddress ||
            addr.isSiteLocalAddress ||
            addr.isLinkLocalAddress ||
            addr.isAnyLocalAddress

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
