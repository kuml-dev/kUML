package dev.kuml.runtime.chain.wasm.ink

import dev.kuml.runtime.chain.wasm.scale.ScaleCodec
import dev.kuml.runtime.chain.wasm.scale.ScaleException
import dev.kuml.runtime.chain.wasm.scale.ScaleReader

/**
 * V3.0.22 — Dekodiert `Contracts.ContractEmitted`-Event-Daten anhand der ink!-ABI-Metadata.
 *
 * **Pure Kotlin, Native-Image-tauglich**: keine RPC-, keine kotlinx-coroutines-Abhaengigkeit.
 *
 * Ablauf:
 * 1. Aus dem `ContractEmitted`-Event kommen `topics` (Vec<H256>) und `data` (Vec<u8>, SCALE-kodiert).
 * 2. Das fuehrende Topic identifiziert den ink!-Event-Typ:
 *    - ink! v5: erstes Topic = `signature_topic` aus der Metadata.
 *    - ink! v4: erstes Topic-Byte = 0-basierter Event-Index (Fallback ueber [InkAbiMetadata.eventByIndex]).
 * 3. Die NICHT-indizierten Felder werden in Deklarationsreihenfolge aus dem `data`-Blob SCALE-dekodiert.
 *    Indizierte Felder liegen als weitere Topics vor (hier als Hex durchgereicht).
 *
 * Das Ergebnis ist ein [DecodedInkEvent] mit dem logischen Event-Namen (→ kUML-Trigger-Name) und
 * einer name→Wert-Map. Die Werte sind primitive Kotlin-Typen ([Long], [Boolean], [String],
 * [ByteArray]) — passend zur weiteren Verarbeitung im kUML-Behaviour-Modell.
 *
 * Robustheit: unbekannte/nicht aufloesbare Feldtypen werden als rohe Rest-Bytes (`ByteArray`)
 * abgelegt statt eine Exception zu werfen, damit ein einzelnes unbekanntes Feld nicht den
 * ganzen Event-Stream kippt. Strukturell kaputte SCALE-Daten werfen [ScaleException].
 *
 * @param maxCollectionLen DoS-Obergrenze, an [ScaleReader] durchgereicht.
 */
public class InkEventDecoder(
    private val abi: InkAbiMetadata,
    private val maxCollectionLen: Int = ScaleCodec.DEFAULT_MAX_COLLECTION_LEN,
) {
    /**
     * Dekodiert ein ContractEmitted-Event.
     *
     * @param topicsHex Topics als 0x-Hex-Strings (erstes Topic identifiziert den Event-Typ).
     * @param dataScale SCALE-kodierter `data`-Blob (nur die nicht-indizierten Felder).
     * @return [DecodedInkEvent] oder null, wenn kein passender Event-Typ gefunden wurde (z.B.
     *   Event eines anderen Contracts) — Aufrufer kann solche Events ueberspringen.
     */
    public fun decode(
        topicsHex: List<String>,
        dataScale: ByteArray,
    ): DecodedInkEvent? {
        val spec = resolveSpec(topicsHex) ?: return null
        val reader = ScaleReader(dataScale, maxCollectionLen)

        val values = LinkedHashMap<String, Any?>()
        // Indizierte Felder kommen aus den Topics (nach dem identifizierenden ersten Topic).
        var topicCursor = 1
        for (field in spec.fields) {
            if (field.indexed) {
                values[field.name] = topicsHex.getOrNull(topicCursor)?.also { topicCursor++ }
            } else {
                values[field.name] = decodeField(field, reader)
            }
        }
        return DecodedInkEvent(spec.label, values)
    }

    private fun resolveSpec(topicsHex: List<String>): InkEventSpec? {
        val first = topicsHex.firstOrNull() ?: return null
        // v5: signature_topic match
        abi.eventBySignatureTopic(first)?.let { return it }
        // v4-Fallback: erstes Byte des Topics als Event-Index interpretieren.
        // Nur anwenden wenn KEIN Event in der ABI ein signature_topic hat (echtes v4-ABI).
        // Bei v5-ABIs, die kein passendes Topic haben, ist es ein Fremd-Contract-Event → null.
        val isV4Abi = abi.events.none { it.signatureTopic != null }
        if (!isV4Abi) return null
        val firstByte = first.removePrefix("0x").take(2).toIntOrNull(16)
        return firstByte?.let { abi.eventByIndex(it) }
    }

    private fun decodeField(
        field: InkEventField,
        reader: ScaleReader,
    ): Any? =
        when (val type = abi.typeOf(field.typeId)) {
            is InkTypeDef.Primitive -> decodePrimitive(type.name, reader)
            is InkTypeDef.FixedArray -> {
                // DoS-Schutz: ABI-kontrollierte len darf maxCollectionLen nicht ueberschreiten
                if (type.len > maxCollectionLen) {
                    throw ScaleException(
                        "FixedArray length ${type.len} exceeds maxCollectionLen $maxCollectionLen " +
                            "(field '${field.name}'); possible malicious ABI",
                    )
                }
                // Spezialfall AccountId32 / H256 etc.: fixed [u8; N] → rohe Bytes
                val elem = abi.typeOf(type.elementTypeId)
                if (elem is InkTypeDef.Primitive && elem.name == "u8") {
                    reader.readFixedBytes(type.len)
                } else {
                    // Heterogene fixed arrays: Element fuer Element (best effort)
                    val list = ArrayList<Any?>(type.len)
                    repeat(type.len) { list.add(decodeByTypeId(type.elementTypeId, reader)) }
                    list
                }
            }
            is InkTypeDef.Sequence -> {
                val elem = abi.typeOf(type.elementTypeId)
                if (elem is InkTypeDef.Primitive && elem.name == "u8") {
                    reader.readByteVec()
                } else {
                    reader.readVec { r -> decodeByTypeId(type.elementTypeId, r) }
                }
            }
            is InkTypeDef.Variant -> {
                // Decode SCALE variant discriminant and return the label string.
                // Covers Option<T> (None=0/Some=1), Result<T,E> (Ok=0/Err=1), and custom ink! enums.
                // We only decode the discriminant here — the payload bytes of the matched variant arm
                // are beyond the scope of the field-level decoder and are returned as raw trailing bytes.
                val discriminant = reader.readU8()
                val label = type.variants[discriminant] ?: "variant($discriminant)"
                label
            }
            else -> {
                // Composite/Tuple/Unknown: position in the stream is not deterministic without
                // full recursive type resolution.  Per the KDoc contract, unknown field types are stored
                // as the raw remaining bytes of the data blob rather than throwing, so that a single
                // unresolvable field does NOT crash the entire event stream.
                reader.readFixedBytes(reader.remaining)
            }
        }

    private fun decodeByTypeId(
        typeId: Int,
        reader: ScaleReader,
    ): Any? =
        when (val t = abi.typeOf(typeId)) {
            is InkTypeDef.Primitive -> decodePrimitive(t.name, reader)
            else -> throw ScaleException("Nested non-primitive typeId=$typeId not supported by default decoder")
        }

    private fun decodePrimitive(
        name: String,
        reader: ScaleReader,
    ): Any =
        when (name) {
            "bool" -> reader.readBool()
            "u8" -> reader.readU8().toLong()
            "i8" -> reader.readU8().toByte().toLong() // Sign-extend 8-bit
            "u16" -> reader.readU16().toLong()
            "i16" -> reader.readU16().toShort().toLong() // Sign-extend 16-bit
            "u32" -> reader.readU32()
            "i32" -> reader.readU32().toInt().toLong() // Sign-extend 32-bit
            "u64" -> reader.readU64()
            "i64" -> reader.readU64() // Bit pattern identical; Kotlin Long is signed → already correct
            "u128", "i128", "u256", "i256" -> reader.readU128Hex()
            "str" -> reader.readString()
            else -> throw ScaleException("Unknown ink! primitive type '$name'")
        }
}

/**
 * Ergebnis des ink!-Event-Decodings.
 *
 * @property name Logischer Event-Name (ink! label) → entspricht dem kUML-Trigger-Namen.
 * @property values Feld-Name → dekodierter Wert ([Long], [Boolean], [String], [ByteArray] oder
 *   Hex-[String] fuer indizierte/u128-Felder).
 */
public data class DecodedInkEvent(
    public val name: String,
    public val values: Map<String, Any?>,
)
