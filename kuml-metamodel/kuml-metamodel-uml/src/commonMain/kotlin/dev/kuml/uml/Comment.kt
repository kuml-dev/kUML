package dev.kuml.uml

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// V0.23.1 — UML Comment / Note.
//
// One of the most basic UML notations (usable in all 14 UML 2.x diagram
// types per the spec): a free-text annotation box with a folded top-right
// corner, optionally attached to zero or more model elements by a dashed
// line.
//
// [UmlComment] is intentionally NOT a [UmlNamedElement] — a note has no
// `name` or `visibility`, only free text ([body]). It is a plain
// [UmlElement], placed directly in [dev.kuml.core.model.KumlDiagram.elements]
// alongside classifiers, exactly like relationships are.
//
// The dashed anchor line to zero or more annotated elements is modelled as
// a first-class [UmlRelationship] ([UmlCommentLink]) rather than as a plain
// `List<String>` field on [UmlComment] itself. Reasons:
//  - Reuses the existing relationship → [dev.kuml.layout.LayoutEdge] pipeline
//    (`EndpointResolver`, ELK routing) for free in diagram types that route
//    edges through ELK (class, package, component, use-case, …).
//  - Keeps the exhaustive `when` in [dev.kuml.uml.ids.UmlIds] callers /
//    `EndpointResolver` / `EdgeRendererDispatcher` honest: adding a new
//    relationship subtype forces every dispatch site to be updated
//    (compile-time safety net), matching this module's existing convention
//    for [UmlAssociation], [UmlDependency], etc.
//  - A comment with zero anchors ("free-standing note") is simply a
//    [UmlComment] with no [UmlCommentLink] referencing it — no special case
//    needed.
//
// Scope (V0.23.1): rendering support covers UML class diagrams (full ELK-
// routed dashed anchor lines, like any other relationship), sequence
// diagrams, and state-machine diagrams (direct-render dashed connector,
// analogous to how those two diagram types already bypass ELK edge routing
// for their own native elements). BPMN, SysML 2, C4, and other UML diagram
// types (object, package, component, deployment, use-case, activity, …) are
// NOT yet wired up in the renderer — [UmlComment] instances placed there are
// silently ignored by the layout bridge and renderer dispatch `else`
// branches, exactly like any other not-yet-supported element. This is a
// known, intentional limitation — see the kUML CHANGELOG entry for V0.23.1.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A UML comment (note) — a free-text annotation, optionally attached to one
 * or more model elements via a dashed line ([UmlCommentLink]).
 *
 * Renders as a rectangle with a folded top-right corner ("dog-ear") — the
 * standard UML note symbol.
 *
 * @property body Free text of the note. May contain newlines; the renderer
 *   wraps long lines to fit the note's width.
 */
@Serializable
data class UmlComment(
    override val id: String,
    val body: String,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlElement

/**
 * A dashed anchor line from a [UmlComment] to one annotated model element.
 *
 * A comment attached to N elements is modelled as N separate
 * [UmlCommentLink] instances (one per anchor), mirroring how
 * [UmlAssociationEnd] models multi-end associations as a list rather than
 * cramming multiple targets into a single relationship instance.
 *
 * Renders as an unadorned dashed line — no arrowhead, no label (per the UML
 * spec, the note-attachment link carries no semantics beyond "this note
 * describes that element").
 *
 * @property commentId [UmlComment.id] of the note.
 * @property annotatedElementId [UmlElement.id] of the annotated element.
 */
@Serializable
data class UmlCommentLink(
    override val id: String,
    val commentId: String,
    val annotatedElementId: String,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlRelationship
