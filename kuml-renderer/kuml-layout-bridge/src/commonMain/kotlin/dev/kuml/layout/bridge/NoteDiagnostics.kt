package dev.kuml.layout.bridge

import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.note.KumlNoteLimits
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
        notes.filter { (_, body) -> NoteLayout.isTruncated(body) }.map { (id, body) ->
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
    public fun forSysml2(model: Sysml2Model): List<NoteDiagnostic> = forBodies(model.comments.map { it.id to it.note.body })

    /** BPMN text annotations of all processes and collaborations of [model]. */
    public fun forBpmn(model: BpmnModel): List<NoteDiagnostic> =
        forBodies(
            (model.processes.flatMap { it.textAnnotations } + model.collaborations.flatMap { it.textAnnotations })
                .map { it.id to it.note.body },
        )

    /** ERM notes of [model]. */
    public fun forErm(model: ErmModel): List<NoteDiagnostic> = forBodies(model.notes.map { it.id to it.note.body })

    /** UML comments of [diagram]. */
    public fun forUml(diagram: KumlDiagram): List<NoteDiagnostic> =
        forBodies(diagram.elements.filterIsInstance<UmlComment>().map { it.id to it.body })
}
