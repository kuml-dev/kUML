package dev.kuml.cli

import dev.kuml.blueprint.constraint.BlueprintCodes
import dev.kuml.blueprint.constraint.BlueprintDuplicateIds
import dev.kuml.bpmn.constraint.BpmnNoteChecker
import dev.kuml.c4.constraint.C4Codes
import dev.kuml.c4.constraint.C4DuplicateRelationships
import dev.kuml.c4.constraint.C4NearDuplicateRelationships
import dev.kuml.core.model.note.KumlNoteCodes
import dev.kuml.core.model.note.KumlNoteIssue
import dev.kuml.core.model.note.NoteKind
import dev.kuml.core.model.note.NotePrefixKinds
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.erm.constraint.ErmNoteChecker
import dev.kuml.layout.bridge.NoteDiagnostics
import dev.kuml.layout.bridge.note.PlacementHintMiss
import dev.kuml.layout.bridge.note.UndrawnNote
import dev.kuml.sysml2.constraint.Sysml2NoteChecker
import dev.kuml.uml.UmlComment

/**
 * Structural note findings (unknown anchors, ...) plus display-limit warnings
 * (`NOTE_TRUNCATED`) for the diagram that was extracted from a script. Shared by
 * `render` (printed as warnings) and `validate` (folded into the result).
 *
 * Messages name note ids and limits, never note bodies.
 *
 * `KUML-NOTE-W006` (prefix/kind mismatch) is opt-in: it is only produced when a [NotePrefixKinds] table is passed (workspace marker `[note-prefixes]`).
 *
 * It also carries the Service Blueprint findings `KUML-BP-W001` (duplicate step ids) and `KUML-BP-W002` (one id used by several element kinds), because this is the single funnel
 * that both `render` and `validate` already consume, and the C4 findings `KUML-C4-W001` / `KUML-C4-W002` (duplicate and near-duplicate relationships).
 */
public object NoteChecks {
    /**
     * Declared-note statistics: total and per [NoteKind]. Counts notes DECLARED in the first diagram of each block
     * (not the notes that were drawn); [blocks] is the number of blocks that contributed a diagram.
     */
    public data class NoteStats(
        val total: Int = 0,
        val byKind: Map<NoteKind, Int> = emptyMap(),
        val blocks: Int = 0,
    ) {
        public operator fun plus(other: NoteStats): NoteStats =
            NoteStats(
                total = total + other.total,
                byKind = (byKind.keys + other.byKind.keys).associateWith { (byKind[it] ?: 0) + (other.byKind[it] ?: 0) },
                blocks = blocks + other.blocks,
            )

        public companion object {
            public val EMPTY: NoteStats = NoteStats()

            /** Statistics of one block from its per-kind counts. */
            public fun ofKinds(kinds: Map<NoteKind, Int>): NoteStats = NoteStats(total = kinds.values.sum(), byKind = kinds, blocks = 1)
        }
    }

    public data class Finding(
        val code: String,
        val isError: Boolean,
        val subjectId: String,
        val message: String,
        /** Script line of the note the finding is about (1-based), `null` when unknown. */
        val sourceLine: Int? = null,
    )

    /** The `KUML-NOTE-W002` findings for notes a render declared but did not draw (see [NoteUndrawnCollector]). */
    public fun undrawn(
        undrawn: List<UndrawnNote>,
        alreadyReported: List<Finding> = emptyList(),
    ): List<Finding> {
        val reported = alreadyReported.filter { it.code == KumlNoteCodes.ANCHOR_NOT_DRAWABLE || it.code == KumlNoteCodes.ANCHOR_UNKNOWN }
        return undrawn
            .filter { u -> reported.none { it.subjectId == u.noteId } }
            .map { u ->
                Finding(
                    code = KumlNoteCodes.ANCHOR_NOT_DRAWABLE,
                    isError = false,
                    subjectId = u.noteId,
                    message = u.message(),
                    sourceLine = u.sourceLine,
                )
            }
    }

    /** The `KUML-NOTE-W004` findings for placement hints a render could not honour (see [dev.kuml.layout.bridge.note.NotePlacementMissCollector]). */
    public fun placementMisses(misses: List<PlacementHintMiss>): List<Finding> =
        misses.map { m ->
            Finding(
                code = m.code ?: KumlNoteCodes.PLACEMENT_NOT_HONOURED,
                isError = false,
                subjectId = m.noteId,
                message = m.message(),
                sourceLine = m.sourceLine,
            )
        }

    public fun of(
        extracted: ExtractedDiagram,
        prefixKinds: NotePrefixKinds = NotePrefixKinds.EMPTY,
    ): List<Finding> {
        val issues: List<KumlNoteIssue>
        val truncated =
            when (extracted) {
                is ExtractedDiagram.Sysml2 -> {
                    issues = Sysml2NoteChecker.check(model = extracted.model, diagram = extracted.diagram)
                    NoteDiagnostics.forSysml2(extracted.model)
                }
                is ExtractedDiagram.Bpmn -> {
                    issues = BpmnNoteChecker.check(extracted.model)
                    NoteDiagnostics.forBpmn(extracted.model)
                }
                is ExtractedDiagram.Erm -> {
                    issues = ErmNoteChecker.check(extracted.model)
                    NoteDiagnostics.forErm(extracted.model)
                }
                is ExtractedDiagram.Uml -> {
                    issues = emptyList()
                    NoteDiagnostics.forUml(extracted.diagram)
                }
                is ExtractedDiagram.C4 -> {
                    issues =
                        dev.kuml.layout.bridge.note.C4NotePlacement
                            .unknownAnchors(extracted.model)
                            .map { (noteId, anchorId) ->
                                KumlNoteIssue(
                                    code = KumlNoteCodes.ANCHOR_UNKNOWN,
                                    severity = KumlNoteIssue.Severity.WARNING,
                                    subjectId = noteId,
                                    message = "Anker unbekannt: $anchorId (Notiz $noteId)",
                                )
                            }
                    NoteDiagnostics.forBodies(extracted.model.notes.map { it.id to it.body })
                }
                is ExtractedDiagram.Blueprint -> {
                    val known =
                        extracted.model.steps
                            .map { it.id }
                            .toSet()
                    issues =
                        extracted.model.noteLinks
                            .filter { it.targetId !in known }
                            .map {
                                KumlNoteIssue(
                                    code = KumlNoteCodes.ANCHOR_UNKNOWN,
                                    severity = KumlNoteIssue.Severity.WARNING,
                                    subjectId = it.noteId,
                                    message = "Anker unbekannt: ${it.targetId} (Notiz ${it.noteId})",
                                )
                            }
                    NoteDiagnostics.forBodies(extracted.model.notes.map { it.id to it.body })
                }
                else -> {
                    issues = emptyList()
                    emptyList()
                }
            }
        val lines = noteLines(extracted)
        val structural =
            issues.map {
                Finding(
                    code = it.code,
                    isError = it.severity == KumlNoteIssue.Severity.ERROR,
                    subjectId = it.subjectId,
                    message = it.message,
                    sourceLine = it.sourceLine ?: lines[it.subjectId],
                )
            }
        val display =
            truncated.map {
                Finding(code = it.code, isError = false, subjectId = it.noteId, message = it.message, sourceLine = lines[it.noteId])
            }
        return structural + display + blueprintFindings(extracted) + c4Findings(extracted) +
            prefixKindFindings(extracted = extracted, prefixKinds = prefixKinds, lines = lines)
    }

    private data class NoteView(
        val id: String,
        val body: String,
        val kind: NoteKind,
    )

    /** Notes of the first diagram with a writable kind; C4 and Blueprint notes carry no kind and are not checked. */
    private fun notesOf(extracted: ExtractedDiagram): List<NoteView> =
        when (extracted) {
            is ExtractedDiagram.Sysml2 -> extracted.model.comments.map { NoteView(id = it.id, body = it.note.body, kind = it.note.kind) }
            is ExtractedDiagram.Bpmn ->
                (
                    extracted.model.processes.flatMap { it.textAnnotations } +
                        extracted.model.collaborations.flatMap { it.textAnnotations }
                ).map { NoteView(id = it.id, body = it.note.body, kind = it.note.kind) }
            is ExtractedDiagram.Erm -> extracted.model.notes.map { NoteView(id = it.id, body = it.note.body, kind = it.note.kind) }
            is ExtractedDiagram.Uml ->
                extracted.diagram.elements
                    .filterIsInstance<UmlComment>()
                    .map { NoteView(id = it.id, body = it.body, kind = it.kind) }
            else -> emptyList()
        }

    /** `KUML-NOTE-W006`: the note text starts with a configured prefix whose expected kind differs from the note's kind. Names the table key, never the text. */
    private fun prefixKindFindings(
        extracted: ExtractedDiagram,
        prefixKinds: NotePrefixKinds,
        lines: Map<String, Int>,
    ): List<Finding> {
        if (prefixKinds.isEmpty) return emptyList()
        return notesOf(extracted).mapNotNull { note ->
            val (key, expected) = prefixKinds.expectedKindFor(note.body) ?: return@mapNotNull null
            if (expected == note.kind) return@mapNotNull null
            Finding(
                code = KumlNoteCodes.PREFIX_KIND_MISMATCH,
                isError = false,
                subjectId = note.id,
                message = "Notiz ${note.id}: Präfix '$key' erwartet Art $expected, die Notiz hat Art ${note.kind}",
                sourceLine = lines[note.id],
            )
        }
    }

    /** `KUML-BP-W001` (duplicated step id) and `KUML-BP-W002` (id shared by several element kinds): one warning each per id of a Service Blueprint model; empty for every other diagram. */
    private fun blueprintFindings(extracted: ExtractedDiagram): List<Finding> =
        when (extracted) {
            is ExtractedDiagram.Blueprint ->
                BlueprintDuplicateIds.duplicateStepIds(extracted.model).map {
                    Finding(
                        code = BlueprintCodes.DUPLICATE_STEP_ID,
                        isError = false,
                        subjectId = it.id,
                        message = it.message(),
                        sourceLine = null,
                    )
                } +
                    BlueprintDuplicateIds.crossKindIds(extracted.model).map {
                        Finding(
                            code = BlueprintCodes.CROSS_KIND_ID,
                            isError = false,
                            subjectId = it.id,
                            message = it.message(),
                            sourceLine = null,
                        )
                    }
            else -> emptyList()
        }

    /**
     * `KUML-C4-W001` / `KUML-C4-W002`: one warning per group of identical / near-identical (case, whitespace)
     * relationships of a C4 model; empty for every other diagram.
     */
    private fun c4Findings(extracted: ExtractedDiagram): List<Finding> =
        when (extracted) {
            is ExtractedDiagram.C4 ->
                C4DuplicateRelationships.find(extracted.model).map {
                    Finding(
                        code = C4Codes.DUPLICATE_RELATIONSHIP,
                        isError = false,
                        subjectId = it.relationshipIds.first(),
                        message = it.message(),
                        sourceLine = null,
                    )
                } +
                    C4NearDuplicateRelationships.findNear(extracted.model).map {
                        Finding(
                            code = C4Codes.NEAR_DUPLICATE_RELATIONSHIP,
                            isError = false,
                            subjectId = it.relationshipIds.first(),
                            message = it.message(),
                            sourceLine = null,
                        )
                    }
            else -> emptyList()
        }

    /**
     * Declared notes of one block, by kind. Counts the notes DECLARED in the first diagram of a block (same scope as [of]),
     * not the notes that were drawn; C4 and Service Blueprint notes carry no kind and count as [NoteKind.NOTE].
     */
    public fun statsOf(extracted: ExtractedDiagram): Map<NoteKind, Int> {
        val kinds: List<NoteKind> =
            when (extracted) {
                is ExtractedDiagram.Sysml2 -> extracted.model.comments.map { it.note.kind }
                is ExtractedDiagram.Bpmn ->
                    (
                        extracted.model.processes.flatMap { it.textAnnotations } +
                            extracted.model.collaborations.flatMap { it.textAnnotations }
                    ).map { it.note.kind }
                is ExtractedDiagram.Erm -> extracted.model.notes.map { it.note.kind }
                is ExtractedDiagram.Uml ->
                    extracted.diagram.elements
                        .filterIsInstance<UmlComment>()
                        .map { it.kind }
                is ExtractedDiagram.C4 -> extracted.model.notes.map { NoteKind.NOTE }
                is ExtractedDiagram.Blueprint -> extracted.model.notes.map { NoteKind.NOTE }
                else -> emptyList()
            }
        return kinds.groupingBy { it }.eachCount()
    }

    /** Script line of each note of [extracted] (note id to line); notes without a known line are absent. */
    private fun noteLines(extracted: ExtractedDiagram): Map<String, Int> {
        val pairs: List<Pair<String, Int?>> =
            when (extracted) {
                is ExtractedDiagram.Sysml2 -> extracted.model.comments.map { it.id to it.note.sourceLine }
                is ExtractedDiagram.Bpmn ->
                    (
                        extracted.model.processes.flatMap { it.textAnnotations } +
                            extracted.model.collaborations.flatMap { it.textAnnotations }
                    ).map { it.id to it.note.sourceLine }
                is ExtractedDiagram.Erm -> extracted.model.notes.map { it.id to it.note.sourceLine }
                is ExtractedDiagram.C4 -> extracted.model.notes.map { it.id to it.sourceLine }
                is ExtractedDiagram.Blueprint -> extracted.model.notes.map { it.id to it.sourceLine }
                else -> emptyList()
            }
        return pairs.mapNotNull { (id, line) -> line?.let { id to it } }.toMap()
    }
}
