package dev.kuml.runtime

import kotlinx.serialization.json.Json

/**
 * Singleton-Json-Konfiguration für Event-/Trace-IO.
 *
 *  - `prettyPrint`: menschen-lesbar
 *  - `classDiscriminator = "type"`: sealed-TraceEntry-Diskriminierung
 *  - `encodeDefaults = false`: kompaktere Files, optionale Felder unterdrückt
 *  - `ignoreUnknownKeys = true`: vorwärtskompatibel
 */
public val KumlRuntimeJson: Json =
    Json {
        prettyPrint = true
        classDiscriminator = "type"
        encodeDefaults = false
        ignoreUnknownKeys = true
    }
