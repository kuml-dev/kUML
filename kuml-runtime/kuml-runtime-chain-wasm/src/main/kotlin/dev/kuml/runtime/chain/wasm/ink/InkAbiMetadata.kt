package dev.kuml.runtime.chain.wasm.ink

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * V3.0.22 — Parser fuer ink!-Contract-ABI-Metadata (Format-Version "4"/"5").
 *
 * **Pure Kotlin, Native-Image-tauglich** (nur kotlinx-serialization-json zum Parsen des
 * JSON-Dokuments — keine RPC-, keine Reflection-Abhaengigkeit).
 *
 * ink!-Metadata (das was `cargo contract build` als `<name>.json` / `metadata.json`
 * erzeugt) beschreibt u.a.:
 * - `spec.messages[]`  — aufrufbare Nachrichten mit `selector` (4-Byte) + Argument-Typen
 * - `spec.events[]`    — emittierbare Events mit Feldern + (ab v5) eindeutigem `signature_topic`
 * - `types[]`          — die zentrale Typregistrierung (PortableType), referenziert ueber numerische IDs
 *
 * Dieser Parser extrahiert genau das fuer Event-Decoding Noetige: pro Event den Namen, die Feldliste
 * (Name + Typ-ID + indexed-Flag) und — falls vorhanden — das `signature_topic`, mit dem ein
 * `ContractEmitted`-Event seinem ink!-Event-Typ zugeordnet werden kann.
 *
 * Robustheit: fehlende optionale Felder fuehren NICHT zu Exceptions; nur strukturell kaputte
 * Pflichtfelder werfen [InkAbiException].
 */
public class InkAbiMetadata private constructor(
    /** Format-Version des Metadata-Dokuments ("4", "5", …). */
    public val version: String,
    /** Alle deklarierten Events, in Deklarationsreihenfolge. */
    public val events: List<InkEventSpec>,
    /** Alle aufrufbaren Messages (selector → spec), fuer Aufruf-Encoding durch Plugin-Autoren. */
    public val messages: List<InkMessageSpec>,
    /** Typregistrierung: PortableType-ID → aufgeloester Primitive-/Composite-Deskriptor. */
    private val types: Map<Int, InkTypeDef>,
) {
    /** Loest eine PortableType-ID in ihren Deskriptor auf (oder [InkTypeDef.Unknown]). */
    public fun typeOf(id: Int): InkTypeDef = types[id] ?: InkTypeDef.Unknown(id)

    /**
     * Findet einen Event-Spec ueber sein `signature_topic` (ink! v5).
     * @return passender [InkEventSpec] oder null wenn keiner matcht.
     */
    public fun eventBySignatureTopic(topicHex: String): InkEventSpec? {
        val norm = topicHex.removePrefix("0x").lowercase()
        return events.firstOrNull { it.signatureTopic?.removePrefix("0x")?.lowercase() == norm }
    }

    /**
     * Findet einen Event-Spec ueber seinen v4-Index (ink! v4 hatte keine signature_topic —
     * das erste Topic eines ContractEmitted-Events war der 0-basierte Event-Index als Byte).
     */
    public fun eventByIndex(index: Int): InkEventSpec? = events.getOrNull(index)

    public companion object {
        /**
         * Parst ein ink!-Metadata-JSON-Dokument.
         *
         * @throws InkAbiException wenn Pflicht-Strukturen fehlen oder kaputt sind.
         */
        public fun parse(root: JsonElement): InkAbiMetadata {
            val obj = root.asObjectOr("ink metadata root must be a JSON object")

            // version kann String ("4") oder { "ink!": { ... } }-Variante sein; wir nehmen beides defensiv.
            val version =
                obj["version"]?.let { v ->
                    (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
                } ?: "unknown"

            val typesArr = obj["types"]?.asArrayOr("ink metadata 'types' must be an array") ?: JsonArray(emptyList())
            val types = parseTypes(typesArr)

            val spec =
                obj["spec"]?.jsonObject
                    ?: throw InkAbiException("ink metadata missing 'spec' object")

            val events = parseEvents(spec["events"]?.asArrayOr("'spec.events' must be an array") ?: JsonArray(emptyList()))
            val messages =
                parseMessages(spec["messages"]?.asArrayOr("'spec.messages' must be an array") ?: JsonArray(emptyList()))

            return InkAbiMetadata(version, events, messages, types)
        }

        private fun parseTypes(arr: JsonArray): Map<Int, InkTypeDef> {
            val out = LinkedHashMap<Int, InkTypeDef>()
            for (entry in arr) {
                val e = entry.jsonObject
                val id = e["id"]?.jsonPrimitive?.intOrNull ?: continue
                val typeNode = e["type"]?.jsonObject ?: continue
                out[id] = resolveTypeDef(id, typeNode)
            }
            return out
        }

        private fun resolveTypeDef(
            id: Int,
            typeNode: JsonObject,
        ): InkTypeDef {
            val def = typeNode["def"]?.jsonObject ?: return InkTypeDef.Unknown(id)
            return when {
                def.containsKey("primitive") -> {
                    val prim = def["primitive"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                    InkTypeDef.Primitive(id, prim)
                }
                def.containsKey("array") -> {
                    val a = def["array"]!!.jsonObject
                    val len = a["len"]?.jsonPrimitive?.intOrNull ?: 0
                    val elem = a["type"]?.jsonPrimitive?.intOrNull ?: 0
                    InkTypeDef.FixedArray(id, len, elem)
                }
                def.containsKey("sequence") -> {
                    val s = def["sequence"]!!.jsonObject
                    val elem = s["type"]?.jsonPrimitive?.intOrNull ?: 0
                    InkTypeDef.Sequence(id, elem)
                }
                def.containsKey("composite") -> {
                    val fields =
                        def["composite"]!!.jsonObject["fields"]?.jsonArray?.mapNotNull { f ->
                            val fo = f.jsonObject
                            val ft = fo["type"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                            InkCompositeField(fo["name"]?.jsonPrimitive?.contentOrNull, ft)
                        } ?: emptyList()
                    InkTypeDef.Composite(id, fields)
                }
                def.containsKey("variant") -> {
                    val variantsMap = LinkedHashMap<Int, String>()
                    def["variant"]?.jsonObject?.get("variants")?.jsonArray?.forEach { v ->
                        val vo = v.jsonObject
                        val discriminant = vo["index"]?.jsonPrimitive?.intOrNull ?: return@forEach
                        val label = vo["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        variantsMap[discriminant] = label
                    }
                    InkTypeDef.Variant(id, variantsMap)
                }
                def.containsKey("tuple") -> {
                    val members =
                        def["tuple"]!!.jsonArray.mapNotNull { it.jsonPrimitive.intOrNull }
                    InkTypeDef.Tuple(id, members)
                }
                else -> InkTypeDef.Unknown(id)
            }
        }

        private fun parseEvents(arr: JsonArray): List<InkEventSpec> =
            arr.mapIndexed { idx, entry ->
                val e = entry.jsonObject
                val label =
                    e["label"]?.jsonPrimitive?.contentOrNull
                        ?: throw InkAbiException("event[$idx] missing 'label'")
                val signatureTopic = e["signature_topic"]?.jsonPrimitive?.contentOrNull
                // ink! v5 uses "args"; ink! v4 used "fields". Check both for backward compatibility
                // so older ink! v4 contracts are not silently parsed as having no event fields.
                val fieldsArray = e["args"]?.jsonArray ?: e["fields"]?.jsonArray
                val fields =
                    fieldsArray?.map { fEl ->
                        val f = fEl.jsonObject
                        val fLabel = f["label"]?.jsonPrimitive?.contentOrNull ?: "_"
                        val indexed = f["indexed"]?.jsonPrimitive?.booleanOrNull ?: false
                        val typeId =
                            f["type"]
                                ?.jsonObject
                                ?.get("type")
                                ?.jsonPrimitive
                                ?.intOrNull
                                ?: f["type"]?.jsonPrimitive?.intOrNull
                                ?: throw InkAbiException("event '$label' arg '$fLabel' missing type id")
                        InkEventField(fLabel, typeId, indexed)
                    } ?: emptyList()
                InkEventSpec(label, idx, signatureTopic, fields)
            }

        private fun parseMessages(arr: JsonArray): List<InkMessageSpec> =
            arr.mapNotNull { entry ->
                val m = entry.jsonObject
                val label = m["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val selector = m["selector"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val mutates = m["mutates"]?.jsonPrimitive?.booleanOrNull ?: false
                InkMessageSpec(label, selector, mutates)
            }

        private fun JsonElement.asObjectOr(msg: String): JsonObject = this as? JsonObject ?: throw InkAbiException(msg)

        private fun JsonElement.asArrayOr(msg: String): JsonArray = this as? JsonArray ?: throw InkAbiException(msg)
    }
}

/** Spezifikation eines ink!-Events. */
public data class InkEventSpec(
    public val label: String,
    /** 0-basierter Deklarationsindex (ink! v4 Event-Diskriminator). */
    public val index: Int,
    /** 32-Byte Hex-Signatur-Topic (ink! v5); null bei v4. */
    public val signatureTopic: String?,
    public val fields: List<InkEventField>,
)

/** Ein Feld eines ink!-Events. */
public data class InkEventField(
    public val name: String,
    /** PortableType-ID in der ABI-Typregistrierung. */
    public val typeId: Int,
    /** ink! `indexed` → landet als Topic, nicht im Event-Daten-Blob. */
    public val indexed: Boolean,
)

/** Spezifikation einer aufrufbaren ink!-Message. */
public data class InkMessageSpec(
    public val label: String,
    /** 4-Byte BLAKE2-Selector als Hex (z.B. "0x633aa551"). */
    public val selector: String,
    /** true wenn die Message `&mut self` ist (zustandsaendernd). */
    public val mutates: Boolean,
)

/** Aufgeloester Deskriptor eines PortableType. */
public sealed interface InkTypeDef {
    public val id: Int

    public data class Primitive(
        override val id: Int,
        public val name: String,
    ) : InkTypeDef

    public data class FixedArray(
        override val id: Int,
        public val len: Int,
        public val elementTypeId: Int,
    ) : InkTypeDef

    public data class Sequence(
        override val id: Int,
        public val elementTypeId: Int,
    ) : InkTypeDef

    public data class Composite(
        override val id: Int,
        public val fields: List<InkCompositeField>,
    ) : InkTypeDef

    public data class Tuple(
        override val id: Int,
        public val memberTypeIds: List<Int>,
    ) : InkTypeDef

    /**
     * A SCALE variant (Rust enum).  [variants] maps each discriminant index to its label.
     * Common ink! variants: Option (0=None, 1=Some), Result (0=Ok, 1=Err), custom enums.
     */
    public data class Variant(
        override val id: Int,
        public val variants: Map<Int, String> = emptyMap(),
    ) : InkTypeDef

    public data class Unknown(
        override val id: Int,
    ) : InkTypeDef
}

/** Feld eines Composite-Typs. */
public data class InkCompositeField(
    public val name: String?,
    public val typeId: Int,
)

/** Wird bei strukturell kaputtem ink!-Metadata-Dokument geworfen. */
public class InkAbiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
