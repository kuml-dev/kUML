package dev.kuml.layout.bridge

import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.note.KumlNoteLimits
import dev.kuml.core.model.note.NoteKind
import dev.kuml.erm.model.ErmModel
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.uml.UmlComment

/** Non-fatal finding about a note, reported by the CLI as a warning. */
public data class NoteDiagnostic(
    val code: String,
    val noteId: String,
    val message: String,
)

/**
 * Display-limit diagnostics for notes. Lives in the layout bridge because the
 * metamodel checkers cannot reach `TextWrap`. Messages name the note id, the
 * original length and the limits, never the body text.
 */
public object NoteDiagnostics {
    public const val NOTE_TRUNCATED: String = "NOTE_TRUNCATED"

    /** Checks `(id, body)` pairs against the display limits. */
    public fun forBodies(notes: List<Pair<String, String>>): List<NoteDiagnostic> =
        forKindedBodies(notes.map { (id, body) -> Triple(id, body, NoteKind.NOTE) })

    /** Checks `(id, body, kind)` triples; the kind matters because a symbol narrows the text column. */
    public fun forKindedBodies(notes: List<Triple<String, String, NoteKind>>): List<NoteDiagnostic> =
        notes.filter { (_, body, kind) -> NoteLayout.isTruncated(body = body, kind = kind) }.map { (id, body, _) ->
            NoteDiagnostic(
                code = NOTE_TRUNCATED,
                noteId = id,
                message =
                    "Note '$id' (${body.length} chars) exceeds the display limit of " +
                        "${KumlNoteLimits.MAX_DISPLAY_CHARS} chars / ${KumlNoteLimits.MAX_DISPLAY_LINES} lines " +
                        "and is rendered truncated",
            )
        }

    /** SysML 2 comments of [model]. */
    public fun forSysml2(model: Sysml2Model): List<NoteDiagnostic> =
        forKindedBodies(model.comments.map { Triple(it.id, it.note.body, it.note.kind) })

    /** BPMN text annotations of all processes and collaborations of [model]. */
    public fun forBpmn(model: BpmnModel): List<NoteDiagnostic> =
        forKindedBodies(
            (model.processes.flatMap { it.textAnnotations } + model.collaborations.flatMap { it.textAnnotations })
                .map { Triple(it.id, it.note.body, it.note.kind) },
        )

    /** ERM notes of [model]. */
    public fun forErm(model: ErmModel): List<NoteDiagnostic> =
        forKindedBodies(model.notes.map { Triple(it.id, it.note.body, it.note.kind) })

    /** UML comments of [diagram]. */
    public fun forUml(diagram: KumlDiagram): List<NoteDiagnostic> =
        forKindedBodies(diagram.elements.filterIsInstance<UmlComment>().map { Triple(it.id, it.body, it.kind) })
}
