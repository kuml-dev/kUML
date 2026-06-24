package dev.kuml.runtime.chain.cosmos.substrate

import dev.kuml.runtime.chain.ChainEvent
import java.util.logging.Logger

/**
 * V3.0.21 — Dekodiert Substrate-Events aus SCALE-encoded System.Events Storage.
 *
 * Substrate-Events sind SCALE-encoded. Diese Klasse implementiert einen minimalen
 * SCALE-Decoder, der ausreicht um:
 * 1. `Contracts.ContractEmitted`-Events aus dem Events-Vec zu extrahieren (für [decodeContractEmitted]).
 * 2. Die ink!-`kuml_identity`-Message-Antwort zu dekodieren (für [decodeIdentity]).
 *
 * Kein vollständiger SCALE-Parser: nur compact-length-prefix + Vec<u8>-Bytes werden gelesen.
 *
 * Pallet- und Varianten-Indizes werden per Konstruktor gesetzt. Da die Indizes je nach Substrate-
 * Runtime variieren (Astar, Shiden, Aleph Zero usw. haben unterschiedliche Indizes für
 * pallet-contracts), müssen sie aus der Runtime-Metadata bei connect()-Zeit bezogen werden.
 * Die Konstanten [CONTRACTS_PALLET_IDX] und [CONTRACT_EMITTED_VARIANT_IDX] sind Defaults für
 * Standard-Substrate-Runtimes und dienen nur als Fallback.
 *
 * Die rohe ContractEmitted-payload landet als `payloadAbi` im [ChainEvent].
 *
 * @param contractsPalletIdx Pallet-Index für pallet-contracts in der Ziel-Runtime.
 * @param contractEmittedVariantIdx Varianten-Index für ContractEmitted in pallet-contracts.
 */
public class SubstrateEventDecoder(
    private val contractsPalletIdx: Int = CONTRACTS_PALLET_IDX,
    private val contractEmittedVariantIdx: Int = CONTRACT_EMITTED_VARIANT_IDX,
) {
    /**
     * Dekodiert alle `Contracts.ContractEmitted`-Events des Ziel-Contracts aus dem
     * hex-encoded System.Events-Storage-Wert.
     *
     * Die Adress-Übereinstimmung wird auf Basis der rohen 32-Byte-AccountId durchgeführt:
     * Der SCALE-encoded AccountId aus dem Event wird als lowercase Hex verglichen mit dem
     * SS58-dekodierten AccountId der [contractAddr]. Das vermeidet Fehler durch
     * verschiedene Adress-Encodings (SS58 ≠ hex).
     *
     * @param eventsHex SCALE-encoded Events (hex, oder "" wenn leer).
     * @param contractAddr SS58-Adresse des gesuchten Contracts (oder "" für alle Contracts).
     * @param height Block-Höhe (für [ChainEvent.blockNumber]).
     * @param blockHash Block-Hash (für [ChainEvent.txHash]).
     * @return Liste der gefundenen [ChainEvent]s (leer wenn keine passenden Events).
     */
    public fun decodeContractEmitted(
        eventsHex: String,
        contractAddr: String,
        height: Long,
        blockHash: String,
    ): List<ChainEvent> {
        if (eventsHex.isEmpty()) return emptyList()
        val bytes = hexToBytes(eventsHex)
        if (bytes.isEmpty()) return emptyList()

        // Decode SS58 address to raw 32-byte accountId for comparison.
        // If contractAddr is empty, all contracts pass the filter.
        val targetAccountIdHex: String? =
            if (contractAddr.isEmpty()) {
                null // null = match all
            } else {
                val decoded = SubstrateAddress.base58Decode(contractAddr)
                if (decoded == null || decoded.size < 34) {
                    // Not a valid SS58 — no events can match
                    return emptyList()
                }
                // SS58: prefix(1 or 2 bytes) + accountId(32 bytes) + checksum(2 bytes)
                val prefixLen = if (decoded.size == 35) 1 else 2
                decoded.copyOfRange(prefixLen, prefixLen + 32).joinToString("") { "%02x".format(it) }
            }

        val events = mutableListOf<ChainEvent>()
        val reader = ScaleReader(bytes)

        // Outer Vec length (compact encoded)
        val count =
            try {
                reader.readCompact()
            } catch (_: Exception) {
                return emptyList()
            }

        repeat(count) {
            try {
                // Each EventRecord in SCALE:
                //   phase: 1 byte (ApplyExtrinsic=0 + u32 index, or Finalization=1, Initialization=2)
                //   For ApplyExtrinsic: phase byte=0 + 4 bytes u32 extrinsic index
                //   For Finalization/Initialization: phase byte only (1 byte)
                //   topics: Vec<Hash> = compact(count) + 32*count bytes
                //   event: pallet_idx(1B) + variant_idx(1B) + event-specific data

                val phaseByte = reader.readByte()
                // ApplyExtrinsic variant has additional u32 (4 bytes) for the extrinsic index
                if (phaseByte == 0) {
                    reader.skipBytes(4)
                }

                // topics: compact length + (32 bytes each)
                val topicsCount = reader.readCompact()
                repeat(topicsCount) { reader.skipBytes(32) }

                // event pallet index + variant index
                val palletIdx = reader.readByte()
                val variantIdx = reader.readByte()

                if (palletIdx == contractsPalletIdx && variantIdx == contractEmittedVariantIdx) {
                    // ContractEmitted: account_id (32 bytes) + data (Vec<u8>)
                    val accountId = reader.readBytes(32)
                    val eventAccountIdHex = accountId.joinToString("") { "%02x".format(it) }
                    val payloadLen = reader.readCompact()
                    val payload = reader.readBytes(payloadLen)

                    if (targetAccountIdHex == null || eventAccountIdHex == targetAccountIdHex) {
                        events.add(
                            ChainEvent(
                                eventType = SubstrateChainAdapter.CONTRACT_EMITTED,
                                payloadAbi = payload,
                                blockNumber = height,
                                txHash = blockHash,
                            ),
                        )
                    }
                } else {
                    // Skip unknown event data.
                    // IMPORTANT: Substrate EventRecord fields are pallet-specific SCALE structs,
                    // NOT necessarily a compact-length-prefixed Vec<u8>. Reading them as Vec<u8>
                    // is a best-effort heuristic and may desync the reader on mixed-event blocks.
                    // A full parser would require Runtime Metadata. If skip fails (desync), we
                    // emit a warning and abort parsing the remaining events in this block.
                    val skipStart = reader.position()
                    try {
                        val dataLen = reader.readCompact()
                        reader.skipBytes(dataLen)
                    } catch (skipEx: Exception) {
                        // Desync detected: could not skip unknown pallet event at current offset.
                        // Return partial results rather than silently losing events without notice.
                        LOGGER.warning(
                            "[SubstrateEventDecoder] Could not skip unknown pallet event " +
                                "(palletIdx=$palletIdx variantIdx=$variantIdx) at byte offset $skipStart — " +
                                "SCALE layout is not Vec<u8>. ${events.size} events decoded before desync. " +
                                "A full parser requires Runtime Metadata.",
                        )
                        return events
                    }
                }
            } catch (_: Exception) {
                // Malformed event record — stop parsing this block.
                return events
            }
        }
        return events
    }

    /**
     * Dekodiert das SCALE-Resultat eines `kuml_identity`-ink!-Message-Calls.
     * Format: Ok-Discriminant (1B) + ink!-return-value = (model_hash Vec<u8>) + (model_uri Vec<u8>) + (schema_version u32).
     *
     * @param resultHex hex-encoded SCALE-Resultat.
     * @return [IdentityResult] mit den dekodierten Feldern.
     * @throws SubstrateChainAdapterException.MalformedResponse bei ungültigem Format.
     */
    public fun decodeIdentity(resultHex: String): IdentityResult {
        if (resultHex.isEmpty()) {
            throw SubstrateChainAdapterException.MalformedResponse("contracts_call returned empty result")
        }
        val bytes = hexToBytes(resultHex)
        val reader = ScaleReader(bytes)

        // Result<T, E> discriminant: 0 = Ok, 1 = Err
        val discriminant = reader.readByte()
        if (discriminant != 0) {
            throw SubstrateChainAdapterException.MalformedResponse(
                "contracts_call returned Err discriminant: $discriminant",
            )
        }

        // model_hash: Vec<u8>
        val hashLen = reader.readCompact()
        val modelHash = reader.readBytes(hashLen)

        // model_uri: Vec<u8> (UTF-8 string in SCALE)
        val uriLen = reader.readCompact()
        val modelUri = String(reader.readBytes(uriLen), Charsets.UTF_8)

        // schema_version: u32 LE
        val schemaVersion = reader.readU32LE()

        return IdentityResult(modelHash, modelUri, schemaVersion)
    }

    /** Gibt zurück ob der Pallet-Index noch auf dem Default-Wert steht. */
    internal fun contractsPalletIdxIsDefault(): Boolean = contractsPalletIdx == CONTRACTS_PALLET_IDX

    /** Gibt zurück ob der Varianten-Index noch auf dem Default-Wert steht. */
    internal fun contractEmittedVariantIdxIsDefault(): Boolean = contractEmittedVariantIdx == CONTRACT_EMITTED_VARIANT_IDX

    /** Einfacher hex → ByteArray Konverter. */
    internal fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").removePrefix("0X")
        if (clean.isEmpty()) return ByteArray(0)
        if (clean.length % 2 != 0) {
            throw SubstrateChainAdapterException.MalformedResponse("Hex string has odd length: '$hex'")
        }
        return try {
            ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: NumberFormatException) {
            throw SubstrateChainAdapterException.MalformedResponse("Invalid hex string: '$hex'", e)
        }
    }

    /** Result of decoding a kuml_identity ink! message. */
    public data class IdentityResult(
        val modelHash: ByteArray,
        val modelUri: String,
        val schemaVersion: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdentityResult) return false
            return modelHash.contentEquals(other.modelHash) &&
                modelUri == other.modelUri &&
                schemaVersion == other.schemaVersion
        }

        override fun hashCode(): Int {
            var r = modelHash.contentHashCode()
            r = 31 * r + modelUri.hashCode()
            r = 31 * r + schemaVersion
            return r
        }
    }

    public companion object {
        private val LOGGER: Logger = Logger.getLogger(SubstrateEventDecoder::class.java.name)

        /**
         * Default-Pallet-Index für `pallet-contracts` in einer Standard-Substrate-Runtime.
         * Dieser Wert variiert je nach Runtime (Astar, Shiden, Aleph Zero usw. haben andere Indizes).
         * Für produktive Nutzung muss der korrekte Index aus der Runtime-Metadata bei connect()-Zeit
         * bezogen und per Konstruktor übergeben werden.
         */
        public const val CONTRACTS_PALLET_IDX: Int = 40

        /**
         * Default-Varianten-Index für `ContractEmitted` in `pallet-contracts`.
         * Muss ebenfalls aus Runtime-Metadata bezogen werden.
         */
        public const val CONTRACT_EMITTED_VARIANT_IDX: Int = 8
    }
}

/**
 * Minimaler SCALE-Reader für sequentiellen Byte-Zugriff.
 * Wirft [SubstrateChainAdapterException.MalformedResponse] bei Buffer-Overflow.
 */
internal class ScaleReader(
    private val data: ByteArray,
) {
    private var pos = 0

    fun readByte(): Int {
        if (pos >= data.size) throw SubstrateChainAdapterException.MalformedResponse("SCALE buffer underflow at pos $pos")
        return data[pos++].toInt() and 0xFF
    }

    fun readBytes(count: Int): ByteArray {
        if (pos + count > data.size) {
            throw SubstrateChainAdapterException.MalformedResponse(
                "SCALE buffer underflow: need $count bytes at pos $pos, have ${data.size - pos}",
            )
        }
        val result = data.copyOfRange(pos, pos + count)
        pos += count
        return result
    }

    fun skipBytes(count: Int) {
        if (pos + count > data.size) {
            throw SubstrateChainAdapterException.MalformedResponse(
                "SCALE skip overflow: need $count bytes at pos $pos",
            )
        }
        pos += count
    }

    /** Gibt die aktuelle Leseposition zurück (für Diagnose-Meldungen). */
    fun position(): Int = pos

    /** Liest einen SCALE compact-encoded unsigned Integer. */
    fun readCompact(): Int {
        val first = readByte()
        return when (first and 0x03) {
            0 -> first ushr 2
            1 -> {
                val second = readByte()
                ((first ushr 2) or (second shl 6))
            }
            2 -> {
                val b1 = readByte()
                val b2 = readByte()
                val b3 = readByte()
                ((first ushr 2) or (b1 shl 6) or (b2 shl 14) or (b3 shl 22))
            }
            else -> {
                // Big-integer mode (mode 3): (first >> 2) + 4 extra bytes follow.
                // Use Long arithmetic to avoid Int shift wrapping (JVM shifts mod 32).
                val extraBytes = (first ushr 2) + 4
                var result = 0L
                for (i in 0 until extraBytes) {
                    result = result or (readByte().toLong() shl (8 * i))
                }
                // Clamp to Int.MAX_VALUE — values beyond Int range cannot be valid SCALE lengths
                // for any realistic on-chain data (and are guarded by the 10 MB response cap).
                if (result > Int.MAX_VALUE.toLong()) {
                    throw SubstrateChainAdapterException.MalformedResponse(
                        "SCALE compact big-integer value $result exceeds Int.MAX_VALUE — likely malformed data",
                    )
                }
                result.toInt()
            }
        }
    }

    /** Liest ein u32 in Little-Endian. */
    fun readU32LE(): Int {
        val b0 = readByte()
        val b1 = readByte()
        val b2 = readByte()
        val b3 = readByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
