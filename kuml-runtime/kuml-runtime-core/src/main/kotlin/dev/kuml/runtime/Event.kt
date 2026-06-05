package dev.kuml.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Ein externes oder internes Ereignis im Sinne der State-Machine-Semantik.
 *
 * OpenTelemetry-kompatibel: `name` + `payload` (attributes) entsprechen
 * dem OTel-Span-Event-Schema. `timestamp` und `id` sind optional und werden
 * von der Runtime intern gesetzt, falls nicht angegeben.
 *
 * @property name Trigger-Name (z.B. `"confirm"`, `"submitPayment"`).
 * @property payload Strukturierte Argumente — für Guards via `event.<key>` zugänglich.
 * @property timestamp Wire-Format ISO 8601 (z.B. `"2026-06-05T09:30:00.123Z"`); `null` lässt
 *   die Runtime den Wert beim Empfang setzen.
 * @property id Optionale stabile Event-ID für Tracing/Idempotenz.
 */
@Serializable
public data class Event(
    public val name: String,
    public val payload: JsonObject = JsonObject(emptyMap()),
    public val timestamp: String? = null,
    public val id: String? = null,
) {
    public companion object {
        /** Hilfsfunktion für Tests: Event ohne Payload. */
        public fun of(name: String): Event = Event(name = name)
    }
}
