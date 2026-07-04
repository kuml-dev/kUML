package dev.kuml.core.script

import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.c4.model.C4Model
import dev.kuml.core.model.KumlDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.uml.UmlSerializersModule
import kotlinx.serialization.json.Json

/**
 * Serializes and deserializes an [ExtractedDiagram] across a process boundary
 * (the [ChildProcessScriptEvaluator] IPC channel).
 *
 * ## Why a hand-written codec instead of `@Serializable ExtractedDiagram`
 *
 * [ExtractedDiagram] is a sealed class defined in `kuml-core-script`, but its
 * payload types live in five independent metamodel modules
 * ([KumlDiagram], [C4Model], [Sysml2Model], [BpmnModel], [BlueprintModel]).
 * Two of these serialization worlds are incompatible in one `Json` instance:
 *
 *  - **UML** ([KumlDiagram]) needs [UmlSerializersModule] because its
 *    `KumlElement` base is an *open* polymorphic type (see [DumpJsonCommand]
 *    in `kuml-cli` for the same split).
 *  - **C4 / SysML 2 / BPMN / Blueprint** models are *sealed* `@Serializable`
 *    hierarchies that need **no** module — and registering the UML module for
 *    them is harmless but unnecessary.
 *
 * Both worlds also require `classDiscriminator = "@type"` because the default
 * `"type"` key collides with `KumlDiagram.type`.
 *
 * The codec therefore tags each envelope with a [Variant] and serializes only
 * the *owning model* (never the diagram separately — for the non-UML variants
 * the diagram is `model.diagrams[diagramIndex]`, so we only need to remember
 * which one was selected). This keeps the wire format small and avoids
 * duplicating the diagram inside its own model.
 *
 * The format is a single JSON object:
 * ```json
 * { "variant": "SYSML2", "diagramIndex": 0, "payload": "<escaped model JSON>" }
 * ```
 *
 * V0.23.3 — introduced for the child-process script sandbox (Welle 2).
 */
internal object ExtractedDiagramCodec {
    /** UML needs the open-polymorphic element registry; sealed metamodels do not. */
    private val umlJson =
        Json {
            serializersModule = UmlSerializersModule
            ignoreUnknownKeys = true
            classDiscriminator = "@type"
        }

    private val modelJson =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "@type"
        }

    /** Which metamodel the payload belongs to. */
    internal enum class Variant { UML, C4, SYSML2, BPMN, BLUEPRINT }

    /** Serializable envelope carrying one [ExtractedDiagram] across the IPC boundary. */
    @kotlinx.serialization.Serializable
    internal data class Envelope(
        val variant: Variant,
        /**
         * Index of the selected diagram within `model.diagrams`. `-1` for UML,
         * whose [KumlDiagram] is self-contained and carries no diagram list.
         */
        val diagramIndex: Int,
        /** The owning model (or [KumlDiagram] for UML) as a JSON string. */
        val payload: String,
    )

    private val envelopeJson = Json { ignoreUnknownKeys = true }

    /** Encodes an [ExtractedDiagram] into a compact JSON envelope string. */
    internal fun encode(extracted: ExtractedDiagram): String {
        val envelope =
            when (extracted) {
                is ExtractedDiagram.Uml ->
                    Envelope(
                        variant = Variant.UML,
                        diagramIndex = -1,
                        payload = umlJson.encodeToString(KumlDiagram.serializer(), extracted.diagram),
                    )
                is ExtractedDiagram.C4 ->
                    Envelope(
                        variant = Variant.C4,
                        diagramIndex = extracted.model.diagrams.indexOf(extracted.diagram),
                        payload = modelJson.encodeToString(C4Model.serializer(), extracted.model),
                    )
                is ExtractedDiagram.Sysml2 ->
                    Envelope(
                        variant = Variant.SYSML2,
                        diagramIndex = extracted.model.diagrams.indexOf(extracted.diagram),
                        payload = modelJson.encodeToString(Sysml2Model.serializer(), extracted.model),
                    )
                is ExtractedDiagram.Bpmn ->
                    Envelope(
                        variant = Variant.BPMN,
                        diagramIndex = extracted.model.diagrams.indexOf(extracted.diagram),
                        payload = modelJson.encodeToString(BpmnModel.serializer(), extracted.model),
                    )
                is ExtractedDiagram.Blueprint ->
                    Envelope(
                        variant = Variant.BLUEPRINT,
                        diagramIndex = extracted.model.diagrams.indexOf(extracted.diagram),
                        payload = modelJson.encodeToString(BlueprintModel.serializer(), extracted.model),
                    )
            }
        return envelopeJson.encodeToString(Envelope.serializer(), envelope)
    }

    /**
     * Decodes a JSON envelope string back into an [ExtractedDiagram].
     *
     * @throws IllegalStateException if the selected diagram index is out of range
     *   (a corrupt or hostile child response) — the caller must treat this as an
     *   evaluation failure, never as a valid diagram.
     */
    internal fun decode(json: String): ExtractedDiagram {
        val envelope = envelopeJson.decodeFromString(Envelope.serializer(), json)
        return when (envelope.variant) {
            Variant.UML ->
                ExtractedDiagram.Uml(
                    umlJson.decodeFromString(KumlDiagram.serializer(), envelope.payload),
                )
            Variant.C4 -> {
                val model = modelJson.decodeFromString(C4Model.serializer(), envelope.payload)
                ExtractedDiagram.C4(model, model.diagrams.selected(envelope.diagramIndex))
            }
            Variant.SYSML2 -> {
                val model = modelJson.decodeFromString(Sysml2Model.serializer(), envelope.payload)
                ExtractedDiagram.Sysml2(model, model.diagrams.selected(envelope.diagramIndex))
            }
            Variant.BPMN -> {
                val model = modelJson.decodeFromString(BpmnModel.serializer(), envelope.payload)
                ExtractedDiagram.Bpmn(model, model.diagrams.selected(envelope.diagramIndex))
            }
            Variant.BLUEPRINT -> {
                val model = modelJson.decodeFromString(BlueprintModel.serializer(), envelope.payload)
                ExtractedDiagram.Blueprint(model, model.diagrams.selected(envelope.diagramIndex))
            }
        }
    }

    private fun <T> List<T>.selected(index: Int): T =
        getOrNull(index)
            ?: error("Decoded model has no diagram at index $index (size=$size); corrupt IPC envelope.")
}
