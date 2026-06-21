package dev.kuml.runtime.chain.move

/**
 * V3.0.20 — 32-Byte-Adresse für Move-VM-Chains (Sui + Aptos).
 *
 * Validierung: "0x" gefolgt von 1..64 Hex-Zeichen. Beide Chains erlauben
 * gekürzte Adressen mit weggelassenen führenden Nullen (z.B. "0x1").
 * Die kanonische [toString]-Form ist links-mit-Nullen auf 64 Hex-Chars
 * gepaddet und lowercase: "0x000…001".
 */
@JvmInline
public value class MoveAddress private constructor(
    private val normalized: String,
) {
    /** Kanonische Form: "0x" + 64 lowercase hex chars. */
    override fun toString(): String = normalized

    /** Roh-Bytes der 32-Byte-Adresse. */
    public fun toBytes(): ByteArray =
        ByteArray(32) { i ->
            normalized.substring(2 + i * 2, 2 + i * 2 + 2).toInt(16).toByte()
        }

    public companion object {
        private val SHORT_REGEX = Regex("^0x[0-9a-fA-F]{1,64}$")

        /**
         * Erzeugt eine normalisierte [MoveAddress] aus einem Roh-Literal.
         *
         * @throws IllegalArgumentException wenn [raw] kein gültiges 0x+1..64-Hex-Literal ist.
         */
        public fun of(raw: String): MoveAddress {
            require(SHORT_REGEX.matches(raw)) {
                "Move address must be '0x' + 1..64 hex chars, was: '$raw'"
            }
            val hex = raw.substring(2).lowercase().padStart(64, '0')
            return MoveAddress("0x$hex")
        }

        /** Non-throwing-Variante für Validierungs-Checks ohne Allokation des Wertes. */
        public fun isValid(raw: String): Boolean = SHORT_REGEX.matches(raw)
    }
}
