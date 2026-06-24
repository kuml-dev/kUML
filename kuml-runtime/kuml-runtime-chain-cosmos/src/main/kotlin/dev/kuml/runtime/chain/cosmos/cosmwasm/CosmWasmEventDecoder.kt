package dev.kuml.runtime.chain.cosmos.cosmwasm

import dev.kuml.runtime.chain.ChainEvent
import dev.kuml.runtime.chain.cosmos.Base64Codec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * V3.0.21 — Dekodiert wasm-Module-Events aus Tendermint block_results.
 *
 * Tendermint-Events haben zwei Formen der Attribut-Kodierung:
 * - **Tendermint < 0.38**: Attribut-Schlüssel und -Werte sind base64-kodiert.
 * - **Tendermint ≥ 0.38**: Attribute sind plain text (keine base64).
 *
 * Der Decoder ermittelt die Form heuristisch: wenn ein Attribut-Key valides base64
 * dekodierbar ist und das Resultat druckbares ASCII enthält → base64-Modus.
 *
 * CosmWasm-Events haben einen `_contract_address`-Key mit der Bech32-Adresse
 * des aufrufenden Contracts. Nur Events mit dieser Adresse werden eingeschlossen.
 * Der `action`-Key liefert den `eventType`; alle restlichen Attribute bilden das `payloadAbi`.
 */
public class CosmWasmEventDecoder {
    /**
     * Extrahiert alle `wasm`-Events des Ziel-Contracts aus einem block_results-Objekt.
     *
     * @param blockResults Result-Objekt des `block_results`-RPC-Calls.
     * @param contractAddr Bech32-Adresse des gesuchten Contracts.
     * @param height Block-Höhe (für [ChainEvent.blockNumber]).
     * @return Liste aller gefundenen [ChainEvent]s (leer wenn keine passenden Events).
     */
    public fun decodeWasmEvents(
        blockResults: JsonElement,
        contractAddr: String,
        height: Long,
    ): List<ChainEvent> {
        val root =
            blockResults as? JsonObject
                ?: return emptyList()
        val events = mutableListOf<ChainEvent>()

        // Tendermint ≥ 0.38: Events in finalize_block_events
        val finalizeEvents = root["finalize_block_events"]?.jsonArray ?: emptyList<JsonElement>()
        for (evt in finalizeEvents) {
            val obj = evt as? JsonObject ?: continue
            extractWasmEvent(obj, contractAddr, height, txHash = "")?.let { events.add(it) }
        }

        // Events aus txs_results[].events
        val txsResults = root["txs_results"]?.jsonArray ?: emptyList<JsonElement>()
        for (txResult in txsResults) {
            val txObj = txResult as? JsonObject ?: continue
            val txHash = txObj["txhash"]?.jsonPrimitive?.content ?: ""
            val txEvents = txObj["events"]?.jsonArray ?: continue
            for (evt in txEvents) {
                val obj = evt as? JsonObject ?: continue
                extractWasmEvent(obj, contractAddr, height, txHash)?.let { events.add(it) }
            }
        }

        return events
    }

    private fun extractWasmEvent(
        evt: JsonObject,
        contractAddr: String,
        height: Long,
        txHash: String,
    ): ChainEvent? {
        val eventType = evt["type"]?.jsonPrimitive?.content ?: return null
        if (eventType != CosmWasmChainAdapter.WASM_EVENT_TYPE) return null

        val attributes = evt["attributes"]?.jsonArray ?: return null
        val decoded = decodeAttributes(attributes)

        // Nur Events des Ziel-Contracts einschließen
        val contractAddress = decoded["_contract_address"] ?: return null
        if (contractAddress != contractAddr) return null

        val action = decoded["action"] ?: decoded["method"] ?: "unknown"

        // Payload: alle Attribute außer _contract_address und action, als JSON-codierter String
        val payloadMap =
            decoded.filterKeys { it != "_contract_address" && it != "action" && it != "method" }
        val payloadBytes = buildPayloadBytes(payloadMap)

        return ChainEvent(
            eventType = action,
            payloadAbi = payloadBytes,
            blockNumber = height,
            txHash = txHash,
        )
    }

    /**
     * Dekodiert ein attributes-JsonArray in eine Map<key, value>.
     *
     * Erkennt base64-Kodierung heuristisch am ersten Key zur Bestimmung des Modus
     * für alle Attribute (Tendermint < 0.38: alle base64; >= 0.38: plain text).
     *
     * Wenn der erste Key ein kurzer plain-text-String ist, der zufällig auch als base64
     * dekodierbar wäre (z.B. "code", "type"), könnte der Modus falsch bestimmt werden.
     * Daher wird für jeden Key **per-Attribut-Fallback** angewendet: wenn im erkannten
     * Modus die Dekodierung zu nicht-druckbaren Zeichen führt, wird das Gegenteil versucht.
     * Das verhindert korrupte Werte bei ambiguösen ersten Keys.
     */
    internal fun decodeAttributes(attributes: JsonArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var isBase64: Boolean? = null // null = noch nicht bestimmt

        for (attr in attributes) {
            val obj = attr as? JsonObject ?: continue
            val rawKey = obj["key"]?.jsonPrimitive?.content ?: continue
            val rawValue = obj["value"]?.jsonPrimitive?.content ?: ""

            if (isBase64 == null) {
                // Bestimme den Modus am ersten Key, aber mit mehreren Kandidaten zur Absicherung
                isBase64 = looksLikeBase64Attribute(rawKey)
            }

            // Per-Attribut-Fallback: wenn erkannter Modus zu ungültigem Ergebnis führt,
            // das Gegenteil versuchen, um Datenverlust bei ambiguösen ersten Keys zu vermeiden.
            val key = decodeWithFallback(rawKey, preferBase64 = isBase64)
            val value = decodeWithFallback(rawValue, preferBase64 = isBase64)
            result[key] = value
        }
        return result
    }

    /**
     * Dekodiert [s] im bevorzugten Modus ([preferBase64]) mit Fallback auf den anderen Modus,
     * falls das Ergebnis nicht-druckbare ASCII-Zeichen enthält.
     */
    private fun decodeWithFallback(
        s: String,
        preferBase64: Boolean,
    ): String {
        if (s.isEmpty()) return s
        return if (preferBase64) {
            val decoded = safeBase64Decode(s)
            // Prüfe ob das Ergebnis druckbares ASCII ist; falls nicht, lies es als plain text
            if (decoded != s && decoded.all { it.code in 0x09..0x7e }) decoded else s
        } else {
            s
        }
    }

    /** Heuristik: ist der Attribut-Key base64-kodiert? */
    private fun looksLikeBase64Attribute(key: String): Boolean {
        if (key.isEmpty() || key.length < 4) return false
        return try {
            val decoded = Base64Codec.decode(key)
            // Resultat sollte druckbares ASCII sein (z.B. "_contract_address", "action")
            decoded.isNotEmpty() && decoded.all { it in 0x20..0x7e }
        } catch (_: Exception) {
            false
        }
    }

    private fun safeBase64Decode(s: String): String =
        try {
            String(Base64Codec.decode(s), Charsets.UTF_8)
        } catch (_: Exception) {
            s
        }

    /** Serialisiert eine Map zu einem kompakten JSON-Byte-Array mit vollem String-Escaping. */
    private fun buildPayloadBytes(map: Map<String, String>): ByteArray {
        if (map.isEmpty()) return ByteArray(0)
        val jsonObj =
            buildJsonObject {
                map.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            }
        return Json.encodeToString(JsonObject.serializer(), jsonObj).toByteArray(Charsets.UTF_8)
    }
}
