package dev.kuml.runtime.chain.move.aptos

import dev.kuml.runtime.chain.ChainEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * V3.0.20 — Übersetzt ein Aptos-Event-JSON-Objekt in einen [ChainEvent].
 *
 * Mapping-Konvention:
 * - `eventType`   = `type` (vollständiger Struct-Tag, z.B. "0x…::kuml::ModelUpdated")
 * - `payloadAbi`  = `data`-JSON-Objekt als kompakter UTF-8-String (re-serialisiert)
 * - `blockNumber` = `version` (Long) — Ledger-Version eindeutig pro Event-Position
 * - `txHash`      = `version.toString()` (Aptos-Events haben keinen eigenen txHash;
 *                   version ist ein stabiler eindeutiger Identifier)
 */
public class AptosEventDecoder {
    /**
     * Dekodiert ein einzelnes Aptos-Event-JSON-Objekt.
     *
     * @throws AptosChainAdapterException.MalformedResponse bei fehlendem Pflichtfeld.
     */
    public fun decode(eventJson: JsonObject): ChainEvent {
        val type =
            eventJson["type"]?.jsonPrimitive?.content
                ?: throw AptosChainAdapterException.MalformedResponse("Event missing 'type'")
        val dataObj =
            eventJson["data"]
                ?: throw AptosChainAdapterException.MalformedResponse("Event missing 'data'")
        // data-JSON-Objekt als kompakter UTF-8-String → payloadAbi
        // toString() von kotlinx-JsonElement ist deterministisch (insertion-order)
        val payloadAbi = dataObj.toString().toByteArray(Charsets.UTF_8)
        val version = eventJson["version"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        return ChainEvent(type, payloadAbi, version, version.toString())
    }

    /**
     * Dekodiert ein JsonArray von Aptos-Events.
     */
    public fun decodeAll(arr: JsonArray): List<ChainEvent> = arr.map { decode(it.jsonObject) }
}
