package dev.kuml.runtime.chain

import dev.kuml.runtime.chain.spec.HexByteArraySerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * EIP-712-Signatur eines Ethereum-Accounts über den kanonischen Hash eines kUML-Modells.
 *
 * Kein `data class` (ByteArray-Felder → Referenz-equals bei Kotlin `data class`).
 * Manuelles [equals]/[hashCode] via `contentEquals`/`contentHashCode` analog
 * zu [ChainEvent] und [ContractIdentity]. JSON via kotlinx.serialization —
 * ByteArray-Felder werden als Lowercase-Hex-String serialisiert ([HexByteArraySerializer]).
 *
 * @property signer     Ethereum-Adresse des Signierers (EIP-55-checksummed, "0x"+40 Hex).
 * @property signature  65-Byte secp256k1-Signatur: r(32) ‖ s(32) ‖ v(1), v ∈ {27, 28}.
 * @property modelHash  32-Byte kanonischer Modell-Hash ([ModelHasher.hashCanonical]).
 * @property timestamp  Unix-Zeit in Sekunden, in den EIP-712-Struct-Hash eingerechnet.
 */
@Serializable
public class ModelSignature(
    public val signer: String,
    @Serializable(with = HexByteArraySerializer::class)
    public val signature: ByteArray,
    @Serializable(with = HexByteArraySerializer::class)
    public val modelHash: ByteArray,
    public val timestamp: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ModelSignature) return false
        return signer == other.signer &&
            signature.contentEquals(other.signature) &&
            modelHash.contentEquals(other.modelHash) &&
            timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = signer.hashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + modelHash.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }

    override fun toString(): String =
        "ModelSignature(signer='$signer', timestamp=$timestamp, " +
            "modelHash=${modelHash.joinToString("") { "%02x".format(it) }}, " +
            "signature=${signature.size}B)"

    /** Serialisiert diese Instanz als formatiertes JSON. */
    public fun toJson(): String = PRETTY_JSON.encodeToString(this)

    public companion object {
        private val PRETTY_JSON = Json { prettyPrint = true }

        /** Deserialisiert eine [ModelSignature] aus dem JSON-String, den [toJson] erzeugt hat. */
        public fun fromJson(json: String): ModelSignature = Json.decodeFromString(json)
    }
}
