package dev.kuml.runtime.chain.evm

import dev.kuml.runtime.chain.ModelHasher
import dev.kuml.runtime.chain.ModelSignature
import java.math.BigInteger

/**
 * EIP-712-Signer und Recovery für kUML-Modell-Signaturen.
 *
 * Implementiert das "ModelCommit"-Typed-Data-Schema (kettenagnostisch — kein chainId,
 * kein verifyingContract). Nutzt `Secp256k1.signRecoverable` (RFC 6979, deterministisch)
 * und `Eip712Verifier.ecRecover` (bestehende Implementierung).
 *
 * **Sicherheitshinweis**: Der Private Key wird niemals geloggt, in Exceptions ausgegeben
 * oder zwischengespeichert. Die `sign`-Methode nimmt ihn als Parameter entgegen und
 * verwirft ihn unmittelbar nach der Signier-Operation.
 *
 * ### EIP-712-Domain (kettenagnostisch)
 * ```
 * EIP712Domain(string name, string version)
 * name    = "kUML"
 * version = "1"
 * ```
 *
 * ### Message-Typ
 * ```
 * ModelCommit(bytes32 modelHash, uint256 timestamp)
 * ```
 */
public class ModelSigner {
    /**
     * Signiert einen kUML-Modell-Quelltext mit dem gegebenen secp256k1-Private-Key.
     *
     * @param modelSource    Roher kUML-Skript-Quelltext (beliebige Zeilenenden).
     * @param privateKeyHex  64-stelliger Hex-String (optionales "0x"-Präfix). Wird niemals geloggt.
     * @return [ModelSignature] mit EIP-712-Signatur, Signer-Adresse (EIP-55), Modell-Hash und Timestamp.
     * @throws IllegalArgumentException wenn [privateKeyHex] kein gültiger secp256k1-Private-Key ist.
     */
    public fun sign(
        modelSource: String,
        privateKeyHex: String,
    ): ModelSignature = sign(modelSource, privateKeyHex, System.currentTimeMillis() / 1000L)

    /**
     * Wie [sign], aber mit festem [timestamp] — für reproduzierbare Tests.
     *
     * `internal` — Produktionscode ruft immer [sign] ohne Timestamp-Argument.
     */
    internal fun sign(
        modelSource: String,
        privateKeyHex: String,
        timestamp: Long,
    ): ModelSignature {
        val d = parsePrivateKey(privateKeyHex)
        val pubPoint = Secp256k1.publicKeyPoint(d)
        val xBytes = Secp256k1.bigIntTo32Bytes(pubPoint.first)
        val yBytes = Secp256k1.bigIntTo32Bytes(pubPoint.second)
        val signerLower = "0x" + Eip712Verifier.addressFromPublicKey(xBytes + yBytes)
        val signerChecksummed = Eip712Verifier.toChecksumAddress(signerLower)

        val modelHash = ModelHasher.hashCanonical(ModelHasher.canonicalize(modelSource))
        val digest = buildDigest(modelHash, timestamp)

        val (r, s, recId) = Secp256k1.signRecoverable(digest, d)
        val rBytes = Secp256k1.bigIntTo32Bytes(r)
        val sBytes = Secp256k1.bigIntTo32Bytes(s)
        val vByte = (recId + 27).toByte()
        val signature = rBytes + sBytes + byteArrayOf(vByte)

        return ModelSignature(
            signer = signerChecksummed,
            signature = signature,
            modelHash = modelHash,
            timestamp = timestamp,
        )
    }

    /**
     * Recovert die Signer-Adresse (EIP-55-checksummed) aus einer [ModelSignature].
     *
     * **Sicherheitsgarantie**: `modelSource` wird frisch gehasht — `sig.modelHash` wird
     * zusätzlich gegen den berechneten Hash geprüft. Eine Signatur kann nicht auf ein
     * fremdes Modell "umgehängt" werden.
     *
     * @param modelSource Roher Quelltext des kUML-Modells, für das die Signatur gilt.
     * @param sig         Die zu prüfende [ModelSignature].
     * @return EIP-55-checksummed Adresse des Signierers.
     * @throws IllegalArgumentException wenn die Signatur ungültig ist oder der modelHash nicht passt.
     */
    public fun recover(
        modelSource: String,
        sig: ModelSignature,
    ): String {
        val computedHash = ModelHasher.hashCanonical(ModelHasher.canonicalize(modelSource))
        require(computedHash.contentEquals(sig.modelHash)) {
            "modelHash in signature does not match the provided modelSource"
        }
        val digest = buildDigest(sig.modelHash, sig.timestamp)
        val recovered =
            Eip712Verifier().recoverAddress(digest, sig.signature)
                ?: throw IllegalArgumentException("signature recovery failed — invalid signature bytes")
        return Eip712Verifier.toChecksumAddress(recovered)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun parsePrivateKey(hex: String): BigInteger {
        val cleaned = hex.removePrefix("0x").removePrefix("0X")
        require(cleaned.length == 64 && cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            "Private key must be a 64-character hex string (0x prefix optional)"
        }
        val d = BigInteger(cleaned, 16)
        require(d > BigInteger.ZERO) { "Private key must be > 0" }
        // N (secp256k1 order) — upper bound check delegated to Secp256k1.signRecoverable
        return d
    }

    private fun buildDigest(
        modelHash: ByteArray,
        timestamp: Long,
    ): ByteArray {
        val domainSeparator = domainSeparator()
        val structHash = structHash(modelHash, timestamp)
        return Eip712Verifier.eip712Digest(domainSeparator, structHash)
    }

    private companion object {
        // EIP-712 domain typeHash: keccak256("EIP712Domain(string name,string version)")
        private val DOMAIN_TYPE_HASH: ByteArray =
            Eip712Verifier.keccak256(
                "EIP712Domain(string name,string version)".toByteArray(Charsets.UTF_8),
            )

        // keccak256("kUML") — domain name encoded as bytes32
        private val NAME_HASH: ByteArray =
            Eip712Verifier.keccak256("kUML".toByteArray(Charsets.UTF_8))

        // keccak256("1") — domain version encoded as bytes32
        private val VERSION_HASH: ByteArray =
            Eip712Verifier.keccak256("1".toByteArray(Charsets.UTF_8))

        // domainSeparator = keccak256(domainTypeHash ‖ nameHash ‖ versionHash)
        private val DOMAIN_SEPARATOR: ByteArray =
            Eip712Verifier.keccak256(DOMAIN_TYPE_HASH + NAME_HASH + VERSION_HASH)

        // structTypeHash: keccak256("ModelCommit(bytes32 modelHash,uint256 timestamp)")
        private val STRUCT_TYPE_HASH: ByteArray =
            Eip712Verifier.keccak256(
                "ModelCommit(bytes32 modelHash,uint256 timestamp)".toByteArray(Charsets.UTF_8),
            )

        fun domainSeparator(): ByteArray = DOMAIN_SEPARATOR

        /**
         * structHash = keccak256(structTypeHash ‖ modelHash(32B) ‖ uint256(timestamp, 32B))
         *
         * - `bytes32` modelHash: direkt als 32 Byte eingerechnet.
         * - `uint256` timestamp: 32 Byte big-endian, links 0-gepaddet.
         */
        fun structHash(
            modelHash: ByteArray,
            timestamp: Long,
        ): ByteArray {
            require(modelHash.size == 32) { "modelHash must be 32 bytes" }
            val tsBytes = Secp256k1.bigIntTo32Bytes(BigInteger.valueOf(timestamp))
            return Eip712Verifier.keccak256(STRUCT_TYPE_HASH + modelHash + tsBytes)
        }
    }
}
