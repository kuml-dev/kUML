package dev.kuml.runtime.trace.otlp

import kotlinx.serialization.json.Json

/**
 * JSON configuration for OTLP serialization.
 *
 * IMPORTANT: Do NOT use [dev.kuml.runtime.KumlRuntimeJson] for OTLP output.
 * KumlRuntimeJson has `classDiscriminator = "type"` which would inject a
 * `"type"` field into every serialized object, breaking OpenTelemetry collectors.
 *
 * This instance uses no classDiscriminator (the default is fine since OTLP
 * data classes are concrete — no sealed hierarchies), `encodeDefaults = false`
 * to keep output compact, and `explicitNulls = false` to omit null fields.
 */
public val OtlpJson: Json =
    Json {
        prettyPrint = true
        encodeDefaults = false
        explicitNulls = false
    }
