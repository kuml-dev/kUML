package dev.kuml.runtime.internal

import dev.kuml.runtime.Event
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Flat Map-View über [Event] für die OCL-Auswertung. */
internal fun Event.toEvalMap(): Map<String, Any?> {
    val payload = payload.mapValues { (_, v) -> v.toEvalValue() }
    return buildMap {
        put("name", name)
        if (id != null) put("id", id)
        if (timestamp != null) put("timestamp", timestamp)
        // Payload-Felder werden flach hineinkopiert; bei Kollision gewinnt der Payload-Eintrag.
        putAll(payload)
    }
}

/** Konvertiert ein JsonElement rekursiv zu nativen Kotlin-Typen für OCL. */
internal fun JsonElement.toEvalValue(): Any? =
    when (this) {
        is JsonNull -> null
        is JsonPrimitive ->
            when {
                isString -> content
                content == "true" || content == "false" -> content.toBooleanStrict()
                else -> content.toIntOrNull() ?: content.toLongOrNull() ?: content.toDoubleOrNull() ?: content
            }
        is JsonObject -> mapValues { (_, v) -> v.toEvalValue() }
        is JsonArray -> map { it.toEvalValue() }
    }
