package dev.kuml.bpmn.model

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.KumlNoteLink
import kotlinx.serialization.Serializable

/** `associationDirection` of a BPMN association (kept for the XML round trip). */
@Serializable
enum class BpmnAssociationDirection { NONE, ONE, BOTH }

/**
 * BPMN text annotation: free text attached to flow nodes or data objects.
 * Rendered as an open square bracket with the text next to it.
 *
 * Not a [BpmnFlowElement]: it takes no part in the control flow.
 */
@Serializable
data class BpmnTextAnnotation(
    val note: KumlNote,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement {
    override val id: String get() = note.id
    override val name: String? get() = null
}

/**
 * BPMN association from a [BpmnTextAnnotation] (`link.noteId`) to the annotated
 * element (`link.targetId`).
 *
 * [direction] is only kept for the BPMN XML round trip. The renderer always
 * draws a dotted line without arrowhead.
 */
@Serializable
data class BpmnAssociation(
    val link: KumlNoteLink,
    val direction: BpmnAssociationDirection = BpmnAssociationDirection.NONE,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement {
    override val id: String get() = link.id
    override val name: String? get() = null
}
