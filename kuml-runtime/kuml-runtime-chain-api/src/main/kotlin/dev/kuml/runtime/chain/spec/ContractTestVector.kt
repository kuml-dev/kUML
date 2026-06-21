package dev.kuml.runtime.chain.spec

import dev.kuml.runtime.chain.ModelHasher
import kotlinx.serialization.Serializable

/**
 * Bekanntes Eingabe/Ausgabe-Paar zur Verifikation von [ModelHasher]-Implementierungen
 * (z.B. eine Solidity-/Off-Chain-Reimplementierung der Kanonisierung).
 *
 * Kein `data class` wegen [ByteArray] (Referenz-equals). [expectedHash] wird über
 * [HexByteArraySerializer] als Lowercase-Hex serialisiert.
 *
 * @property description Was dieser Vektor abdeckt.
 * @property modelSource Roher kUML-Skript-Quelltext (vor Kanonisierung).
 * @property expectedHash 32-Byte SHA-256 von ModelHasher.hashCanonical(canonicalize(modelSource)).
 * @property expectedCanonical Erwartete kanonische Normalform (Ausgabe von canonicalize).
 */
@Serializable
public class ContractTestVector(
    public val description: String,
    public val modelSource: String,
    @Serializable(with = HexByteArraySerializer::class)
    public val expectedHash: ByteArray,
    public val expectedCanonical: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContractTestVector) return false
        return description == other.description &&
            modelSource == other.modelSource &&
            expectedHash.contentEquals(other.expectedHash) &&
            expectedCanonical == other.expectedCanonical
    }

    override fun hashCode(): Int {
        var r = description.hashCode()
        r = 31 * r + modelSource.hashCode()
        r = 31 * r + expectedHash.contentHashCode()
        r = 31 * r + expectedCanonical.hashCode()
        return r
    }

    public companion object {
        /**
         * Standard-Verifikations-Vektoren. Erzeugt deterministisch aus realen
         * Modell-Quelltexten — expectedHash/expectedCanonical werden NICHT hardcoded,
         * sondern aus [ModelHasher] abgeleitet, damit die Vektoren immer self-consistent
         * sind. Der Test prüft, dass jeder Vektor reproduzierbar ist.
         */
        public val STANDARD_VECTORS: List<ContractTestVector> =
            listOf(
                "minimal model" to "model {\n    state(\"A\")\n}\n",
                "CRLF normalised to LF" to "model {\r\n    state(\"A\")\r\n}\r\n",
                "tabs expanded, blanks removed" to "model {\n\n\tstate(\"A\")\n\n\tstate(\"B\")\n}\n",
                "trailing whitespace stripped" to "model {   \n    state(\"A\")  \n}  \n",
                "empty source -> single LF" to "",
                "two-state transition model" to "model {\n    state(\"Idle\")\n    state(\"Run\")\n    transition(\"Idle\", \"Run\")\n}\n",
            ).map { (desc, src) ->
                val canonical = ModelHasher.canonicalize(src)
                ContractTestVector(
                    description = desc,
                    modelSource = src,
                    expectedHash = ModelHasher.hashCanonical(canonical),
                    expectedCanonical = canonical,
                )
            }
    }
}
