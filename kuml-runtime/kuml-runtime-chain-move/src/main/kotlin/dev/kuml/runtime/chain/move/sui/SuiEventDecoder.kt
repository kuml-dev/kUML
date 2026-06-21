package dev.kuml.runtime.chain.move.sui

import dev.kuml.runtime.chain.ChainEvent
import dev.kuml.runtime.chain.move.Base64Decoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * V3.0.20 — Übersetzt ein Sui-Event-JSON-Objekt in einen [ChainEvent].
 *
 * Mapping-Konvention:
 * - `eventType`  = `type` (voller Struct-Tag, z.B. "0x…::kuml::KumlModelUpdated")
 * - `payloadAbi` = Base64-dekodiertes `bcs`-Feld (leer wenn fehlend)
 * - `blockNumber` = `checkpoint` (dezimal) — fallback 0 wenn fehlend
 * - `txHash`     = `id.txDigest`
 */
public class SuiEventDecoder {
    /**
     * Dekodiert ein einzelnes Sui-Event-JSON-Objekt.
     *
     * @throws SuiChainAdapterException.MalformedResponse bei fehlendem Pflichtfeld.
     */
    public fun decode(eventJson: JsonObject): ChainEvent {
        val type =
            eventJson["type"]?.jsonPrimitive?.content
                ?: throw SuiChainAdapterException.MalformedResponse("Event missing 'type'")
        val bcs = eventJson["bcs"]?.jsonPrimitive?.content ?: ""
        val payloadAbi = Base64Decoder.decode(bcs)
        val checkpoint = eventJson["checkpoint"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val txDigest =
            eventJson["id"]
                ?.jsonObject
                ?.get("txDigest")
                ?.jsonPrimitive
                ?.content
                ?: throw SuiChainAdapterException.MalformedResponse("Event missing 'id.txDigest'")
        return ChainEvent(type, payloadAbi, checkpoint, txDigest)
    }

    /**
     * Dekodiert ein JsonArray von Sui-Events.
     *
     * @throws SuiChainAdapterException.MalformedResponse wenn [dataArray] kein JsonArray ist.
     */
    public fun decodeAll(dataArray: JsonElement): List<ChainEvent> {
        val arr =
            dataArray as? JsonArray
                ?: throw SuiChainAdapterException.MalformedResponse("Expected JsonArray of events")
        return arr.map { decode(it.jsonObject) }
    }
}
