package dev.kuml.runtime.trace.otlp

/**
 * Deterministic span/trace ID generator using FNV-1a 64-bit hash.
 *
 * All IDs are hex strings padded to the OTLP-required lengths:
 * - traceId: 32 hex chars (128-bit)
 * - spanId:  16 hex chars (64-bit)
 *
 * Determinism ensures stable golden-file tests across JVM versions.
 */
public object OtlpIds {
    /** Generate a 32-hex-char trace ID from a model ID. */
    public fun traceId(modelId: String): String {
        val hi = fnv1a64("$modelId/0").toULong().toString(16).padStart(16, '0')
        val lo = fnv1a64("$modelId/1").toULong().toString(16).padStart(16, '0')
        return hi + lo
    }

    /** Generate a 16-hex-char span ID from model ID, vertex ID, and enter sequence number. */
    public fun spanId(
        modelId: String,
        vertexId: String,
        enterSeqNo: Long,
    ): String = fnv1a64("$modelId|$vertexId|$enterSeqNo").toULong().toString(16).padStart(16, '0')

    /** Generate a 16-hex-char span ID from a free-form seed string. */
    public fun spanId(seed: String): String = fnv1a64(seed).toULong().toString(16).padStart(16, '0')

    /** FNV-1a 64-bit hash. Returns a non-zero value (0 is mapped to 1). */
    internal fun fnv1a64(input: String): Long {
        var hash = -3750763034362895579L // FNV offset basis: 0xcbf29ce484222325
        for (b in input.encodeToByteArray()) {
            hash = hash xor (b.toLong() and 0xFFL)
            hash *= 1099511628211L
        }
        return if (hash == 0L) 1L else hash
    }
}
