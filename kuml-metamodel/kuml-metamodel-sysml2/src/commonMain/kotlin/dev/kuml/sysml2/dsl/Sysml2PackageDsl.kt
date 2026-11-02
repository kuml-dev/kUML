package dev.kuml.sysml2.dsl

import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.Sysml2Limits
import dev.kuml.sysml2.Sysml2Package
import dev.kuml.sysml2.Sysml2View
import dev.kuml.sysml2.Sysml2Viewpoint

private const val MAX_ENTRY_CHARS: Int = 512

private fun checkedEntry(
    list: List<String>,
    text: String,
    what: String,
): String {
    require(text.isNotBlank()) { "$what must not be blank" }
    require(text.length <= MAX_ENTRY_CHARS) { "$what longer than $MAX_ENTRY_CHARS characters" }
    require(list.size < Sysml2Limits.VIEW_ENTRIES) { "more than ${Sysml2Limits.VIEW_ENTRIES} ${what}s" }
    return text
}

/** Scope of `viewpoint("Name") { stakeholder("..."); concern("...") }` (wave 2I-a). */
@Sysml2Dsl
class ViewpointScope internal constructor() {
    private val stakeholders = mutableListOf<String>()
    private val concerns = mutableListOf<String>()

    /** A stakeholder of this viewpoint (plain text). */
    fun stakeholder(text: String) {
        stakeholders += checkedEntry(list = stakeholders, text = text, what = "stakeholder")
    }

    /** A concern of this viewpoint (plain text). */
    fun concern(text: String) {
        concerns += checkedEntry(list = concerns, text = text, what = "concern")
    }

    internal fun stakeholders(): List<String> = stakeholders.toList()

    internal fun concerns(): List<String> = concerns.toList()
}

/** Scope of `view("Name") { satisfies("Viewpoint"); expose("..."); filter("...") }` (wave 2I-a). */
@Sysml2Dsl
class ViewScope internal constructor() {
    private val viewpointIds = mutableListOf<String>()
    private val exposes = mutableListOf<String>()
    private val filters = mutableListOf<String>()

    /** This view satisfies the viewpoint with the given name (id `viewpoint:<name>`); an unknown name is reported by the checker. */
    fun satisfies(viewpointName: String) {
        satisfiesById("viewpoint:$viewpointName")
    }

    /** This view satisfies the given viewpoint. */
    fun satisfies(viewpoint: Sysml2Viewpoint) {
        satisfiesById(viewpoint.id)
    }

    /** Id variant of [satisfies]. */
    fun satisfiesById(viewpointId: String) {
        require(viewpointId.isNotBlank()) { "viewpoint id must not be blank" }
        require(viewpointIds.size < Sysml2Limits.VIEW_ENTRIES) { "more than ${Sysml2Limits.VIEW_ENTRIES} satisfied viewpoints" }
        if (viewpointId !in viewpointIds) viewpointIds += viewpointId
    }

    /** What this view exposes (plain text such as `Vehicle::*`; not evaluated). */
    fun expose(text: String) {
        exposes += checkedEntry(list = exposes, text = text, what = "expose entry")
    }

    /** A filter of this view (plain text such as `@Safety`; not evaluated). */
    fun filter(text: String) {
        filters += checkedEntry(list = filters, text = text, what = "filter entry")
    }

    internal fun viewpointIds(): List<String> = viewpointIds.toList()

    internal fun exposes(): List<String> = exposes.toList()

    internal fun filters(): List<String> = filters.toList()
}

/** Scope of `packageDiagram("Name") { include(...) }` (wave 2I-a). The `layout { }` block comes from the shared base. */
@Sysml2Dsl
class PackageDiagramBuilder internal constructor(
    modelBuilder: Sysml2ModelBuilder,
) : Sysml2NoteBuilderBase(modelBuilder) {
    private val ids = mutableListOf<String>()

    /** Draws the package with its whole sub-tree and all members. */
    fun include(pkg: Sysml2Package) {
        ids += pkg.id
    }

    /** Draws a definition as a card (on root level when its package is not drawn). */
    fun include(definition: Sysml2Definition) {
        ids += definition.id
    }

    /** Draws a view card. */
    fun include(view: Sysml2View) {
        ids += view.id
    }

    /** Draws a viewpoint card. */
    fun include(viewpoint: Sysml2Viewpoint) {
        ids += viewpoint.id
    }

    /** Includes any package, definition, view or viewpoint by id (forward references). */
    fun includeById(id: String) {
        ids += id
    }

    internal fun ids(): List<String> = ids.distinct()
}
