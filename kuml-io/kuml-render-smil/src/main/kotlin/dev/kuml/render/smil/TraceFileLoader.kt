package dev.kuml.render.smil

import dev.kuml.runtime.KumlRuntimeJson
import dev.kuml.runtime.TraceFile
import kotlinx.serialization.SerializationException
import java.io.File

/**
 * Loads a [TraceFile] from a JSON file on disk.
 *
 * Enforces a file-size cap before reading to prevent heap exhaustion from malicious inputs.
 * Wraps [SerializationException] into [IllegalArgumentException] without leaking raw file bytes.
 *
 * V3.1.31 — STM + Activity SMIL Renderers
 */
public object TraceFileLoader {
    /** Default maximum file size in bytes (5 MB). */
    public const val DEFAULT_MAX_BYTES: Long = 5_000_000L

    /**
     * Loads a [TraceFile] from [file].
     *
     * @param file The JSON file to load. Must exist and be readable.
     * @param maxBytes Maximum allowed file size in bytes. Defaults to [DEFAULT_MAX_BYTES].
     * @return The decoded [TraceFile].
     * @throws IllegalArgumentException if the file exceeds [maxBytes], the JSON is malformed,
     *   or the schema string does not match [TraceFile.SCHEMA].
     */
    public fun load(
        file: File,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): TraceFile {
        val fileSize = file.length()
        require(fileSize <= maxBytes) {
            "Trace file '${file.name}' is $fileSize bytes which exceeds the maximum of $maxBytes bytes. " +
                "Reduce trace length or increase maxBytes."
        }

        val raw = file.readText(Charsets.UTF_8)

        val traceFile =
            try {
                KumlRuntimeJson.decodeFromString(TraceFile.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalArgumentException(
                    "Invalid trace file '${file.name}': not valid kuml.trace.v1 JSON. " +
                        "Cause: ${e.message?.take(120)}",
                )
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "Invalid trace file '${file.name}': malformed content. Cause: ${e.message?.take(120)}",
                )
            }

        require(traceFile.schema == TraceFile.SCHEMA) {
            "Trace file '${file.name}' has schema '${traceFile.schema}', " +
                "expected '${TraceFile.SCHEMA}'."
        }

        return traceFile
    }
}
