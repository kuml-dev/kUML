package dev.kuml.bpmn.dsl

import dev.kuml.bpmn.model.BpmnAssociation
import dev.kuml.bpmn.model.BpmnTextAnnotation
import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.KumlNoteLink

/** Builds one annotation plus one association per distinct anchor. Ids: `<owner>_annotation_<n>`, `<owner>_association_<n>_<k>`. */
internal fun buildTextAnnotation(
    ownerId: String,
    counter: Int,
    text: String,
    anchors: Array<out String>,
    id: String?,
): Pair<BpmnTextAnnotation, List<BpmnAssociation>> {
    val noteId = id ?: "${ownerId}_annotation_$counter"
    val annotation = BpmnTextAnnotation(note = KumlNote(id = noteId, body = text))
    val links =
        anchors.distinct().mapIndexed { k, target ->
            BpmnAssociation(link = KumlNoteLink(id = "${ownerId}_association_${counter}_${k + 1}", noteId = noteId, targetId = target))
        }
    return annotation to links
}
