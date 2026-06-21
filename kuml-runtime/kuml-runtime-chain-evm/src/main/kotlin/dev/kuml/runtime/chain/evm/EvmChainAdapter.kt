package dev.kuml.runtime.chain.evm

import dev.kuml.runtime.chain.BlockClock
import dev.kuml.runtime.chain.ChainEvent
import dev.kuml.runtime.chain.ContractIdentity
import dev.kuml.runtime.chain.KumlChainAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.URI

/**
 * Strategie zur Validierung von RPC-URLs. Ermöglicht Dependency-Injection
 * für Tests (No-op-Implementierung) ohne die SSRF-Schutzlogik aus dem
 * Produktionscode zu entfernen.
 *
 * Die Default-Implementierung [RpcUrlValidator.Default] führt die vollständige
 * SSRF-Prüfung durch (Schema, IP-Range-Blocking). Für Unit-Tests mit einem
 * MockRpcServer auf localhost kann [RpcUrlValidator.NoOp] injiziert werden.
 *
 * Hinweis: Nur IP-Literale werden auf private Ranges geprüft. Hostname-basierter
 * SSRF (DNS Rebinding) erfordert eine Allowlist auf Aufrufer-Seite oder einen
 * transparenten HTTP-Proxy — dies ist explizit dokumentiert und liegt außerhalb
 * des Schutzumfangs dieser Klasse.
 */
public fun interface RpcUrlValidator {
    /**
     * Validiert [rpcUrl]. Wirft [IllegalArgumentException] wenn die URL
     * gegen SSRF-Schutzregeln verstößt.
     */
    public fun validate(rpcUrl: String)

    public companion object {
        /** Produktion: vollständige SSRF-Validierung. */
        public val Default: RpcUrlValidator = RpcUrlValidator { url -> EvmChainAdapter.validateRpcUrl(url) }

        /**
         * Test-only: No-op-Validator, der alle URLs akzeptiert.
         * Erlaubt Verbindungen zu localhost/127.0.0.1 in Unit-Tests mit MockRpcServer.
         * Darf nur in Testcode instanziiert werden.
         */
        public val NoOp: RpcUrlValidator = RpcUrlValidator { /* alle URLs akzeptiert */ }
    }
}

/**
 * V3.0.2 — EVM-(Ethereum/L2-)Implementierung von [KumlChainAdapter].
 *
 * Bindet ein on-chain registriertes kUML-Behaviour-Modell an einen EVM-Contract:
 * - [connect] liest modelHash/modelUri/schemaVersion via eth_call aus dem Contract.
 * - [subscribe] pollt `eth_getLogs` ab dem aktuellen Block (Cold Flow, unendlich).
 * - [replay] liefert historische Events ab fromBlock bis zum aktuellen Kopf (terminiert).
 * - Reorg-Schutz: Block-Hashes werden mitgeführt; ein Hash-Mismatch an bereits
 *   gesehener Höhe wirft [EvmChainAdapterException.ReorgDetected].
 *
 * Thread-Safety: Diese Klasse ist **nicht thread-safe**. [connect] muss genau einmal
 * und vollständig abgeschlossen sein, bevor [subscribe] oder [replay] aufgerufen
 * werden. Parallele Aufrufe von [connect] aus mehreren Coroutinen führen zu
 * undefiniertem Verhalten (Race Condition auf mutablem internen State).
 *
 * JVM-only. Kein GraalVM-Native-Anspruch.
 *
 * @param pollIntervalMillis Poll-Abstand für [subscribe] (Default 12_000 ≈ Block-Zeit).
 * @param logPageSize Block-Range pro eth_getLogs-Page in [replay] und [subscribe] (Default 2_000).
 * @param clientFactory Factory-Funktion zum Erzeugen eines [EvmJsonRpcClient] (injizierbar für Tests).
 * @param eventDecoder Decoder für Log-Objekte (injizierbar für Tests).
 * @param urlValidator Strategie zur RPC-URL-Validierung. Default: vollständiger SSRF-Schutz.
 *   Für Unit-Tests mit MockRpcServer auf localhost: [RpcUrlValidator.NoOp] injizieren.
 */
public class EvmChainAdapter(
    private val pollIntervalMillis: Long = 12_000L,
    private val logPageSize: Long = 2_000L,
    private val clientFactory: (String) -> EvmJsonRpcClient = { url -> EvmJsonRpcClient(url) },
    private val eventDecoder: EvmEventDecoder = EvmEventDecoder(),
    private val urlValidator: RpcUrlValidator = RpcUrlValidator.Default,
) : KumlChainAdapter {
    private var rpcClient: EvmJsonRpcClient? = null
    private var blockClock: EvmBlockClock? = null
    private var connectedAddress: String? = null

    override suspend fun connect(
        rpcUrl: String,
        contractAddress: String,
    ): ContractIdentity {
        require(rpcUrl.isNotBlank()) { "rpcUrl must not be blank" }
        urlValidator.validate(rpcUrl)
        require(EVM_ADDRESS_REGEX.matches(contractAddress)) {
            "contractAddress must be a valid Ethereum address (0x + 40 hex chars), was: '$contractAddress'"
        }

        val client = clientFactory(rpcUrl)
        rpcClient = client

        val clock = EvmBlockClock(client)
        blockClock = clock
        connectedAddress = contractAddress

        // Read modelHash() — returns bytes32
        val hashHex = client.ethCall(contractAddress, SELECTOR_MODEL_HASH)
        val modelHash = AbiCodec.decodeBytes32(hashHex)

        // Read modelUri() — returns string (dynamic ABI)
        val uriHex = client.ethCall(contractAddress, SELECTOR_MODEL_URI)
        val modelUri = AbiCodec.decodeString(uriHex)

        // Read schemaVersion() — returns uint256
        val versionHex = client.ethCall(contractAddress, SELECTOR_SCHEMA_VERSION)
        val schemaVersion = AbiCodec.decodeUint(versionHex)

        return ContractIdentity(
            address = contractAddress,
            modelHash = modelHash,
            modelUri = modelUri,
            schemaVersion = schemaVersion,
        )
    }

    override fun subscribe(): Flow<ChainEvent> =
        flow {
            val client = requireClient()
            val address = requireAddress()
            var lastBlock = client.ethBlockNumber()
            var lastBlockHash: String? = null

            // Capture hash of the starting block for reorg detection
            if (lastBlock > 0) {
                val blk = client.ethGetBlockByNumber(lastBlock)
                lastBlockHash = blk.jsonObject["hash"]?.jsonPrimitive?.content
            }

            while (true) {
                val head = client.ethBlockNumber()

                if (head > lastBlock) {
                    // Reorg check: re-read last seen block's hash
                    if (lastBlockHash != null && lastBlock > 0) {
                        val rereadBlk = client.ethGetBlockByNumber(lastBlock)
                        val rereadHash = rereadBlk.jsonObject["hash"]?.jsonPrimitive?.content
                        if (rereadHash != null && rereadHash != lastBlockHash) {
                            throw EvmChainAdapterException.ReorgDetected(
                                reorgFromBlock = lastBlock,
                                expectedBlockHash = lastBlockHash,
                                actualBlockHash = rereadHash,
                            )
                        }
                    }

                    // Long-Overflow-Schutz: lastBlock + 1 darf nicht überlaufen.
                    // Praktischer EVM-Bereich liegt weit unter Long.MAX_VALUE,
                    // aber böswillige RPC-Antworten könnten einen Extremwert liefern.
                    val fromBlock = Math.addExact(lastBlock, 1L)

                    // Paginierung analog zu replay(): logPageSize-Blöcke pro Seite.
                    // Verhindert DoS durch einzelnen getLogs-Call über Millionen Blöcke
                    // (z.B. nach langem Ausfall oder manipulierter ethBlockNumber-Antwort).
                    //
                    // Overflow-Schutz: `current + logPageSize - 1` kann bei current nahe
                    // Long.MAX_VALUE überlaufen. Stattdessen: subtraktion von head (sicher,
                    // da head >= current durch Loop-Bedingung garantiert). Wenn der verbleibende
                    // Range kleiner als logPageSize ist, nehmen wir direkt head als pageEnd.
                    var current = fromBlock
                    while (current <= head) {
                        val pageEnd = if (head - current < logPageSize - 1) head else current + logPageSize - 1
                        val logsJson = client.ethGetLogs(address, current, pageEnd)
                        val events = eventDecoder.decodeAll(logsJson)
                        for (event in events) {
                            emit(event)
                        }
                        current = Math.addExact(pageEnd, 1L)
                    }

                    // Update last seen block hash
                    val headBlk = client.ethGetBlockByNumber(head)
                    lastBlockHash = headBlk.jsonObject["hash"]?.jsonPrimitive?.content
                    lastBlock = head
                }

                delay(pollIntervalMillis)
            }
        }

    override fun blockClock(): BlockClock = blockClock ?: error("blockClock() called before connect()")

    override fun replay(fromBlock: Long): Flow<ChainEvent> {
        require(fromBlock >= 0) { "fromBlock must be >= 0, was $fromBlock" }
        return flow {
            val client = requireClient()
            val address = requireAddress()
            val head = client.ethBlockNumber()

            // Overflow-Schutz: `current + logPageSize - 1` kann bei current nahe
            // Long.MAX_VALUE überlaufen. Stattdessen: subtraktion von head (sicher,
            // da head >= current durch Loop-Bedingung garantiert). Wenn der verbleibende
            // Range kleiner als logPageSize ist, nehmen wir direkt head als pageEnd.
            var current = fromBlock
            while (current <= head) {
                val pageEnd = if (head - current < logPageSize - 1) head else current + logPageSize - 1
                val logsJson = client.ethGetLogs(address, current, pageEnd)
                val events = eventDecoder.decodeAll(logsJson)
                for (event in events) {
                    emit(event)
                }
                current = Math.addExact(pageEnd, 1L)
            }
        }
    }

    /** Sichtbar für Tests: liefert den intern aufgebauten Client nach connect(), sonst null. */
    internal fun rpcClientOrNull(): EvmJsonRpcClient? = rpcClient

    private fun requireClient(): EvmJsonRpcClient = rpcClient ?: error("EvmChainAdapter not connected — call connect() first")

    private fun requireAddress(): String = connectedAddress ?: error("EvmChainAdapter not connected — call connect() first")

    public companion object {
        /**
         * ABI function selectors des erwarteten kUML-Registry-Contracts.
         * Berechnet als keccak256(<signature>)[0..3], 4 Bytes.
         *
         * modelHash()    → keccak256("modelHash()")    = 0x2b97e1fa
         * modelUri()     → keccak256("modelUri()")     = 0x9b8e1bfe
         * schemaVersion() → keccak256("schemaVersion()") = 0xce1b815f
         */
        public const val SELECTOR_MODEL_HASH: String = "0x2b97e1fa"
        public const val SELECTOR_MODEL_URI: String = "0x9b8e1bfe"
        public const val SELECTOR_SCHEMA_VERSION: String = "0xce1b815f"

        /**
         * Regex für gültige Ethereum-Adressen: "0x" gefolgt von genau 40 Hex-Zeichen.
         */
        private val EVM_ADDRESS_REGEX = Regex("^0x[0-9a-fA-F]{40}$")

        /**
         * Erlaubte URL-Schemata für RPC-Endpunkte. SSRF-Schutz: file://, ftp://,
         * gopher://, jar:// und andere Schemata werden abgelehnt.
         */
        private val ALLOWED_RPC_SCHEMES = setOf("http", "https")

        /**
         * Validiert [rpcUrl] gegen SSRF-Angriffe:
         * 1. Schema muss http oder https sein (kein file://, ftp://, gopher:// etc.)
         * 2. Host darf keine Private-IP-Range adressieren (127.x, 10.x, 172.16-31.x,
         *    192.168.x, 169.254.x — AWS-Metadata, interne Netze, loopback).
         *
         * Der InetAddress-Lookup findet nur statt wenn der Host ein IP-Literal ist
         * (kein DNS-Lookup). Bei Hostnamen wird nur das Schema geprüft — ein vollständiger
         * DNS-Rebinding-Schutz würde einen HTTP-Proxy oder eine Allowlist erfordern.
         */
        internal fun validateRpcUrl(rpcUrl: String) {
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
            // Nur für IP-Literale direkt prüfen (kein DNS-Lookup für Hostnamen)
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

        /** Heuristik: sieht der Host-String wie ein IPv4/IPv6-Literal aus? */
        private fun looksLikeIpLiteral(host: String): Boolean {
            // IPv6 literal ist in eckigen Klammern in der URI, aber URI.host liefert ohne Klammern
            if (host.startsWith("[") || host.contains(":")) return true
            // IPv4: nur Ziffern und Punkte
            return host.all { it.isDigit() || it == '.' }
        }

        /** Prüft ob eine IP-Adresse zu einer privaten, loopback oder link-local Range gehört. */
        private fun isPrivateOrLoopback(addr: InetAddress): Boolean =
            addr.isLoopbackAddress ||
                addr.isSiteLocalAddress ||
                addr.isLinkLocalAddress ||
                addr.isAnyLocalAddress
    }
}
