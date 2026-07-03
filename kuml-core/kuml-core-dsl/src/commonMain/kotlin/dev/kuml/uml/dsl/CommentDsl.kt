package dev.kuml.uml.dsl

import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.ids.UmlIds

// ─────────────────────────────────────────────────────────────────────────────
// UML Comment / Note (V0.23.1).
//
// Scope: Class, Sequence, and State Machine diagrams only — the three
// overloads below are extension functions on [UmlModelScope],
// [UmlInteractionScope], and [UmlStateMachineScope] respectively. Other
// diagram types (BPMN, SysML 2, C4, Component, Use Case, Activity, Object,
// Deployment, Package, Profile, Composite Structure, Communication, Timing,
// Interaction-Overview, …) do NOT get a `comment()` DSL function — the
// underlying `UmlComment`/`UmlCommentLink` metamodel types exist everywhere
// (registered in `UmlSerializersModule`), but only the three diagram types
// above have renderer + layout-bridge support (see `UmlLayoutBridge`,
// `NodeRendererDispatcher`, `EdgeRendererDispatcher`).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Adds a free-text [UmlComment] (UML note) to a class diagram or UML model,
 * optionally anchored to one or more elements via dashed [UmlCommentLink]s.
 *
 * Scope (V0.23.1): class diagrams (this overload, via [UmlModelScope]),
 * sequence diagrams (see the [UmlInteractionScope] overload), and
 * state-machine diagrams (see the [UmlStateMachineScope] overload). Other
 * diagram types do not have this DSL function.
 *
 * ```kotlin
 * classDiagram(name = "Order Domain") {
 *     val order = classOf(name = "Order") { /* … */ }
 *     comment(
 *         text = "Encapsulates the full order lifecycle from placement to fulfillment.",
 *         anchors = arrayOf(order.id),
 *     )
 * }
 * ```
 *
 * @param text Free text of the note. May contain newlines.
 * @param anchors [dev.kuml.uml.UmlElement.id] values of zero or more elements
 *   this comment annotates. Each anchor produces a separate [UmlCommentLink].
 * @param id Explicit ID override; auto-derived via [UmlIds.comment] otherwise.
 * @return The built [UmlComment].
 */
public fun UmlModelScope.comment(
    text: String,
    vararg anchors: String,
    id: String? = null,
): UmlComment {
    val commentId =
        id ?: UmlIds.disambiguate(UmlIds.comment(containerId ?: "root", nextCommentIndex(takenIds)), takenIds)
    takenIds += commentId
    val c = UmlComment(id = commentId, body = text)
    addComment(c)
    for (anchorId in anchors) {
        val linkId =
            UmlIds.disambiguate(UmlIds.commentLink(commentId, anchorId), takenIds)
        takenIds += linkId
        addRelationship(UmlCommentLink(id = linkId, commentId = commentId, annotatedElementId = anchorId))
    }
    return c
}

/**
 * Overload — anchors passed as builder handles ([UmlNamedElement]) instead of
 * raw IDs.
 *
 * Requires **at least one** anchor — `comment(text = "…")` with zero anchors
 * resolves to the [String] vararg overload above (an empty `vararg` array is
 * otherwise ambiguous between the two overloads; the required first argument
 * here removes the ambiguity).
 */
public fun UmlModelScope.comment(
    text: String,
    firstAnchor: UmlNamedElement,
    vararg moreAnchors: UmlNamedElement,
    id: String? = null,
): UmlComment =
    comment(
        text = text,
        anchors = (listOf(firstAnchor) + moreAnchors).map { it.id }.toTypedArray(),
        id = id,
    )

/**
 * Adds a free-text [UmlComment] (UML note) to a sequence diagram, optionally
 * anchored to one or more lifelines/messages via dashed [UmlCommentLink]s.
 *
 * See the [UmlModelScope] overload of [comment] for the class-diagram variant
 * and the general scope note.
 */
public fun UmlInteractionScope.comment(
    text: String,
    vararg anchors: String,
    id: String? = null,
): UmlComment {
    val commentId = id ?: UmlIds.disambiguate(UmlIds.comment(interactionId, nextCommentIndex(takenIds)), takenIds)
    takenIds += commentId
    val c = UmlComment(id = commentId, body = text)
    addComment(c)
    for (anchorId in anchors) {
        val linkId = UmlIds.disambiguate(UmlIds.commentLink(commentId, anchorId), takenIds)
        takenIds += linkId
        addCommentLink(UmlCommentLink(id = linkId, commentId = commentId, annotatedElementId = anchorId))
    }
    return c
}

/**
 * Overload — anchors passed as builder handles ([UmlNamedElement]) instead of
 * raw IDs.
 *
 * Requires **at least one** anchor — `comment(text = "…")` with zero anchors
 * resolves to the [String] vararg overload above (an empty `vararg` array is
 * otherwise ambiguous between the two overloads; the required first argument
 * here removes the ambiguity).
 */
public fun UmlInteractionScope.comment(
    text: String,
    firstAnchor: UmlNamedElement,
    vararg moreAnchors: UmlNamedElement,
    id: String? = null,
): UmlComment =
    comment(
        text = text,
        anchors = (listOf(firstAnchor) + moreAnchors).map { it.id }.toTypedArray(),
        id = id,
    )

/**
 * Adds a free-text [UmlComment] (UML note) to a state-machine diagram,
 * optionally anchored to one or more states/transitions via dashed
 * [UmlCommentLink]s.
 *
 * See the [UmlModelScope] overload of [comment] for the class-diagram variant
 * and the general scope note.
 */
public fun UmlStateMachineScope.comment(
    text: String,
    vararg anchors: String,
    id: String? = null,
): UmlComment {
    val commentId = id ?: UmlIds.disambiguate(UmlIds.comment(stateMachineId, nextCommentIndex(takenIds)), takenIds)
    takenIds += commentId
    val c = UmlComment(id = commentId, body = text)
    addComment(c)
    for (anchorId in anchors) {
        val linkId = UmlIds.disambiguate(UmlIds.commentLink(commentId, anchorId), takenIds)
        takenIds += linkId
        addCommentLink(UmlCommentLink(id = linkId, commentId = commentId, annotatedElementId = anchorId))
    }
    return c
}

/**
 * Overload — anchors passed as builder handles ([UmlNamedElement]) instead of
 * raw IDs.
 *
 * Requires **at least one** anchor — `comment(text = "…")` with zero anchors
 * resolves to the [String] vararg overload above (an empty `vararg` array is
 * otherwise ambiguous between the two overloads; the required first argument
 * here removes the ambiguity).
 */
public fun UmlStateMachineScope.comment(
    text: String,
    firstAnchor: UmlNamedElement,
    vararg moreAnchors: UmlNamedElement,
    id: String? = null,
): UmlComment =
    comment(
        text = text,
        anchors = (listOf(firstAnchor) + moreAnchors).map { it.id }.toTypedArray(),
        id = id,
    )

// ── Index helpers ─────────────────────────────────────────────────────────────
//
// [UmlIds.comment] needs a 1-based index (comments have no name to derive an
// ID from). [UmlModelScope]/[UmlContainerScope] does not track a running
// comment counter, so we derive a stable-enough candidate from the current
// [UmlContainerScope.takenIds] size and let [UmlIds.disambiguate] resolve any
// collision. This mirrors how [generalization]/[dependency] etc. rely on
// [UmlIds.disambiguate] as the collision safety net rather than plumbing a
// dedicated counter through every scope.

private fun nextCommentIndex(taken: Set<String>): Int = taken.size + 1
