package dev.kuml.runtime.chain.evm

import dev.kuml.runtime.chain.ChainEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Übersetzt ein einzelnes `eth_getLogs`/Log-JSON-Objekt in einen [ChainEvent].
 *
 * Mapping-Konvention:
 * - `eventType`   = `topics[0]` als 0x-Hex (Event-Signatur-Hash, keccak256 der Signatur).
 * - `payloadAbi`  = `data`-Feld (0x-Hex) → roher ByteArray (ABI-kodierte non-indexed Args).
 *   Indexed Topics (topics[1..]) werden nicht an payloadAbi angehängt
 *   (MVP: nur das Data-Segment; indexed args bleiben über [indexedTopics] abrufbar).
 * - `blockNumber` = `blockNumber` (Hex-Quantity → Long).
 * - `txHash`      = `transactionHash` (0x-Hex, as-is).
 */
public class EvmEventDecoder {
    /** @throws EvmChainAdapterException.MalformedResponse bei fehlendem Pflichtfeld. */
    public fun decode(logJson: JsonObject): ChainEvent {
        val topicsArr =
            logJson["topics"]?.jsonArray
                ?: throw EvmChainAdapterException.MalformedResponse("Log missing 'topics' field")

        if (topicsArr.isEmpty()) {
            throw EvmChainAdapterException.MalformedResponse("Log 'topics' array is empty — no event signature hash")
        }

        val eventType = topicsArr[0].jsonPrimitive.content

        val dataHex = logJson["data"]?.jsonPrimitive?.content ?: "0x"
        val payloadAbi = hexToBytes(dataHex)

        val blockNumberHex =
            logJson["blockNumber"]?.jsonPrimitive?.content
                ?: throw EvmChainAdapterException.MalformedResponse("Log missing 'blockNumber' field")
        val blockNumber = EvmJsonRpcClient.parseHexQuantity(blockNumberHex)

        val txHash =
            logJson["transactionHash"]?.jsonPrimitive?.content
                ?: throw EvmChainAdapterException.MalformedResponse("Log missing 'transactionHash' field")

        return ChainEvent(
            eventType = eventType,
            payloadAbi = payloadAbi,
            blockNumber = blockNumber,
            txHash = txHash,
        )
    }

    /** Bequem-Overload: nimmt ein JsonArray von Logs und mappt jedes. */
    public fun decodeAll(logsJson: JsonElement): List<ChainEvent> {
        val arr =
            logsJson as? JsonArray
                ?: throw EvmChainAdapterException.MalformedResponse("Expected JsonArray of logs")
        return arr.map { decode(it.jsonObject) }
    }

    /** Indexed Topics (topics[1..]) eines Log-Objekts als 0x-Hex-Liste. */
    public fun indexedTopics(logJson: JsonObject): List<String> {
        val topicsArr = logJson["topics"]?.jsonArray ?: return emptyList()
        return topicsArr.drop(1).map { it.jsonPrimitive.content }
    }

    public companion object {
        /** "0xdeadbeef" → ByteArray. Leeres/"0x" → leeres Array. Wirft MalformedResponse bei Nicht-Hex. */
        public fun hexToBytes(hex: String): ByteArray {
            val clean = hex.removePrefix("0x").removePrefix("0X")
            if (clean.isEmpty()) return ByteArray(0)
            if (clean.length % 2 != 0) {
                throw EvmChainAdapterException.MalformedResponse("Hex string has odd length: '$hex'")
            }
            return try {
                ByteArray(clean.length / 2) { i ->
                    clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } catch (e: NumberFormatException) {
                throw EvmChainAdapterException.MalformedResponse("Invalid hex string: '$hex'", e)
            }
        }
    }
}
