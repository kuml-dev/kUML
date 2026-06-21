package dev.kuml.runtime.chain

import java.time.Instant

/**
 * Ein einzelnes On-Chain-Event.
 *
 * Normale `class` statt `data class`, weil [ByteArray]-Felder in einer `data class`
 * referenz-basiertes `equals`/`hashCode` erzeugen — was Assertions in Tests bricht.
 * Stattdessen: manuelles `contentEquals`-basiertes `equals`/`hashCode`.
 *
 * @property eventType Logischer Event-/Trigger-Name (entspricht dem Trigger-Namen
 *   im kUML-Behaviour-Modell, z.B. `"OrderPlaced"`).
 * @property payloadAbi ABI-kodierte Roh-Argumente des Events (chain-spezifisches Encoding).
 * @property blockNumber Block, in dem das Event emittiert wurde (≥ 0).
 * @property txHash Transaktions-Hash (Hex, chain-spezifisches Präfix, z.B. `"0x…"`).
 */
public class ChainEvent(
    public val eventType: String,
    public val payloadAbi: ByteArray,
    public val blockNumber: Long,
    public val txHash: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChainEvent) return false
        return eventType == other.eventType &&
            payloadAbi.contentEquals(other.payloadAbi) &&
            blockNumber == other.blockNumber &&
            txHash == other.txHash
    }

    override fun hashCode(): Int {
        var r = eventType.hashCode()
        r = 31 * r + payloadAbi.contentHashCode()
        r = 31 * r + blockNumber.hashCode()
        r = 31 * r + txHash.hashCode()
        return r
    }

    override fun toString(): String =
        "ChainEvent(eventType='$eventType', blockNumber=$blockNumber, txHash='$txHash', payloadAbi=${payloadAbi.size}B)"
}

/**
 * Identität eines on-chain registrierten kUML-Modells, wie sie von
 * [KumlChainAdapter.connect] zurückgegeben wird.
 *
 * @property address Contract-Adresse (Hex, chain-spezifisches Format).
 * @property modelHash Kanonischer SHA-256-Hash des kUML-Modells.
 *   Berechnet via [ModelHasher.hashCanonical] über [ModelHasher.canonicalize].
 * @property modelUri Auflösbare URI des Modell-Quelltexts (z.B. `"ipfs://…"` oder `"https://…"`).
 * @property schemaVersion Versions-Diskriminator des On-Chain-Schemas (für Forward-Kompatibilität).
 */
public class ContractIdentity(
    public val address: String,
    public val modelHash: ByteArray,
    public val modelUri: String,
    public val schemaVersion: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContractIdentity) return false
        return address == other.address &&
            modelHash.contentEquals(other.modelHash) &&
            modelUri == other.modelUri &&
            schemaVersion == other.schemaVersion
    }

    override fun hashCode(): Int {
        var r = address.hashCode()
        r = 31 * r + modelHash.contentHashCode()
        r = 31 * r + modelUri.hashCode()
        r = 31 * r + schemaVersion
        return r
    }

    override fun toString(): String =
        "ContractIdentity(address='$address', modelUri='$modelUri', schemaVersion=$schemaVersion, " +
            "modelHash=${modelHash.joinToString("") { "%02x".format(it) }})"
}

/**
 * Eine kryptografische Signatur über einen kUML-Event oder eine Modell-Identität.
 *
 * @property scheme Signatur-Schema-Bezeichner (z.B. `"secp256k1"`, `"ed25519"`).
 * @property signature Roh-Signaturbytes.
 * @property signer Signer-Identität (Adresse oder Public-Key-Fingerprint, Hex).
 */
public class ChainSignature(
    public val scheme: String,
    public val signature: ByteArray,
    public val signer: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChainSignature) return false
        return scheme == other.scheme &&
            signature.contentEquals(other.signature) &&
            signer == other.signer
    }

    override fun hashCode(): Int {
        var r = scheme.hashCode()
        r = 31 * r + signature.contentHashCode()
        r = 31 * r + signer.hashCode()
        return r
    }

    override fun toString(): String = "ChainSignature(scheme='$scheme', signer='$signer', signature=${signature.size}B)"
}

/**
 * Block-basierte Uhr. Liefert ledger-gebundene Zeit statt Wall-Clock, damit
 * Replays deterministisch reproduzierbar bleiben.
 *
 * Nutzt [java.time.Instant] — konsistent mit der übrigen kUML-Runtime
 * (`StateMachineRuntime`, `ModelFingerprint`). Kein kotlinx-datetime.
 */
public interface BlockClock {
    /** Zeitstempel des aktuellen Blocks als [Instant]. */
    public fun currentTime(): Instant

    /** Aktuelle Block-Höhe (monoton wachsend). */
    public fun currentBlock(): Long
}
