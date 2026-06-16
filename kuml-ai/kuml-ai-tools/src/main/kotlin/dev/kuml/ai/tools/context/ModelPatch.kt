package dev.kuml.ai.tools.context

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.time.Instant

/**
 * Append-only audit-log entry describing a single mutation that an @Tool applied
 * against the AgentEditingContext clone. V3.0.25 reads this list to render the
 * diff-preview UI and to perform per-patch accept/reject.
 *
 * Patches DO NOT include the post-state model — they describe the operation
 * symbolically. Re-application is deterministic via PatchReplayer (V3.0.25).
 */
@Serializable
public sealed interface ModelPatch {
    /** ULID; stable across snapshot/restore. */
    public val patchId: String

    /** ISO-8601 instant. */
    public val appliedAt: String

    /** The currentDiagramId at apply-time (may be null). */
    public val diagramId: String?

    @Serializable
    public data class AddElement(
        override val patchId: String,
        override val appliedAt: String,
        override val diagramId: String?,
        /** e.g. "uml.class", "c4.person", "sysml2.partdef" */
        val elementKind: String,
        val elementId: String,
        val name: String,
        val payload: Map<String, String> = emptyMap(),
    ) : ModelPatch

    @Serializable
    public data class RemoveElement(
        override val patchId: String,
        override val appliedAt: String,
        override val diagramId: String?,
        val elementId: String,
    ) : ModelPatch

    @Serializable
    public data class UpdateAttribute(
        override val patchId: String,
        override val appliedAt: String,
        override val diagramId: String?,
        val ownerId: String,
        val attributeId: String,
        /** e.g. "type", "visibility", "defaultValue" */
        val field: String,
        val newValue: String,
    ) : ModelPatch

    @Serializable
    public data class RenameElement(
        override val patchId: String,
        override val appliedAt: String,
        override val diagramId: String?,
        val elementId: String,
        val oldName: String,
        val newName: String,
    ) : ModelPatch

    @Serializable
    public data class AddRelationship(
        override val patchId: String,
        override val appliedAt: String,
        override val diagramId: String?,
        /** e.g. "uml.generalization", "c4.relationship" */
        val relationshipKind: String,
        val relationshipId: String,
        val sourceId: String,
        val targetId: String,
        val payload: Map<String, String> = emptyMap(),
    ) : ModelPatch

    public companion object {
        private val rng = SecureRandom()

        /**
         * Generates a ULID-shaped string (26 chars, Crockford base32).
         *
         * Uses System.currentTimeMillis() for the timestamp component and
         * SecureRandom for the randomness component. Suitable for audit-log
         * patchIds — not a cryptographic primitive.
         */
        public fun newId(): String {
            val ts = System.currentTimeMillis()
            val randBytes = ByteArray(10).also { rng.nextBytes(it) }
            // Encode timestamp (48 bits) + random (80 bits) = 128 bits → 26 Crockford base32 chars
            val enc = CrockfordBase32.encode(ts, randBytes)
            return enc
        }

        /** Returns the current instant as an ISO-8601 string. */
        public fun nowIso(): String = Instant.now().toString()
    }
}

/** Minimal Crockford base32 encoder for ULID generation. */
private object CrockfordBase32 {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun encode(
        tsMillis: Long,
        rand: ByteArray,
    ): String {
        // Build a 128-bit value: 48-bit timestamp + 80-bit random
        // Use a LongArray approach: [high 64 bits][low 64 bits]
        val tsHi = (tsMillis shr 13) and 0x7FFFL
        val tsLo = tsMillis and 0x1FFFL
        // 26 chars, 5 bits each
        val chars = CharArray(26)
        // Combine ts (48 bits = ~10 chars) + rand (80 bits = ~16 chars)
        // Simplified: pack into two longs then encode
        val hi: Long = (tsMillis shl 16) or ((rand[0].toLong() and 0xFF) shl 8) or (rand[1].toLong() and 0xFF)
        val lo: Long =
            ((rand[2].toLong() and 0xFF) shl 56) or
                ((rand[3].toLong() and 0xFF) shl 48) or
                ((rand[4].toLong() and 0xFF) shl 40) or
                ((rand[5].toLong() and 0xFF) shl 32) or
                ((rand[6].toLong() and 0xFF) shl 24) or
                ((rand[7].toLong() and 0xFF) shl 16) or
                ((rand[8].toLong() and 0xFF) shl 8) or
                (rand[9].toLong() and 0xFF)
        // Encode hi (64 bits → 13 chars) + lo (64 bits → 13 chars) = 26 chars
        var v = hi
        for (i in 12 downTo 0) {
            chars[i] = ALPHABET[(v and 0x1F).toInt()]
            v = v ushr 5
        }
        v = lo
        for (i in 25 downTo 13) {
            chars[i] = ALPHABET[(v and 0x1F).toInt()]
            v = v ushr 5
        }
        return String(chars)
    }
}
