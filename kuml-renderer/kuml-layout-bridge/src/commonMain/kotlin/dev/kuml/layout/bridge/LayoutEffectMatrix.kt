package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutOption

/**
 * Every diagram art a `layout { }` block can be written for (PAL wave 2B). One entry per real drawing
 * pipeline, so the [LayoutEffectMatrix] can say precisely which option has an effect where.
 */
public enum class SpecDiagramKind {
    UML_CLASS,
    UML_OBJECT,
    UML_COMPOSITE_STRUCTURE,
    UML_PACKAGE,
    UML_DEPLOYMENT,
    UML_PROFILE,
    UML_COMPONENT,
    UML_USE_CASE,
    UML_STATE,
    UML_ACTIVITY,
    UML_COMMUNICATION,
    UML_INTERACTION_OVERVIEW,
    UML_SEQUENCE,
    UML_TIMING,
    SYSML2_BDD,
    SYSML2_IBD,
    SYSML2_STM,
    SYSML2_REQ,
    SYSML2_UC,
    SYSML2_ACT,
    SYSML2_PAR,
    SYSML2_SEQ,
    SYSML2_PKG,
    C4,
    ERM,
    BPMN_PROCESS,
    BPMN_COLLABORATION,
    BPMN_CHOREOGRAPHY,
    BPMN_CONVERSATION,
    BLUEPRINT,
    JOURNEY,
}

/** Maps diagrams onto their [SpecDiagramKind], so every pipeline asks the matrix the same question. */
public object SpecDiagramKinds {
    /** The kind of a UML diagram of [type] (any non-UML type falls back to [SpecDiagramKind.UML_CLASS]). */
    public fun of(type: dev.kuml.core.model.DiagramType): SpecDiagramKind =
        when (type) {
            dev.kuml.core.model.DiagramType.CLASS -> SpecDiagramKind.UML_CLASS
            dev.kuml.core.model.DiagramType.SEQUENCE -> SpecDiagramKind.UML_SEQUENCE
            dev.kuml.core.model.DiagramType.STATE -> SpecDiagramKind.UML_STATE
            dev.kuml.core.model.DiagramType.COMPONENT -> SpecDiagramKind.UML_COMPONENT
            dev.kuml.core.model.DiagramType.USE_CASE -> SpecDiagramKind.UML_USE_CASE
            dev.kuml.core.model.DiagramType.OBJECT -> SpecDiagramKind.UML_OBJECT
            dev.kuml.core.model.DiagramType.COMPOSITE_STRUCTURE -> SpecDiagramKind.UML_COMPOSITE_STRUCTURE
            dev.kuml.core.model.DiagramType.PACKAGE -> SpecDiagramKind.UML_PACKAGE
            dev.kuml.core.model.DiagramType.DEPLOYMENT -> SpecDiagramKind.UML_DEPLOYMENT
            dev.kuml.core.model.DiagramType.PROFILE -> SpecDiagramKind.UML_PROFILE
            dev.kuml.core.model.DiagramType.ACTIVITY -> SpecDiagramKind.UML_ACTIVITY
            dev.kuml.core.model.DiagramType.COMMUNICATION -> SpecDiagramKind.UML_COMMUNICATION
            dev.kuml.core.model.DiagramType.TIMING -> SpecDiagramKind.UML_TIMING
            dev.kuml.core.model.DiagramType.INTERACTION_OVERVIEW -> SpecDiagramKind.UML_INTERACTION_OVERVIEW
            else -> SpecDiagramKind.UML_CLASS
        }

    /** The kind of a SysML 2 [diagram]. */
    public fun of(diagram: dev.kuml.sysml2.Sysml2Diagram): SpecDiagramKind =
        when (diagram) {
            is dev.kuml.sysml2.BdDiagram -> SpecDiagramKind.SYSML2_BDD
            is dev.kuml.sysml2.IbdDiagram -> SpecDiagramKind.SYSML2_IBD
            is dev.kuml.sysml2.UcDiagram -> SpecDiagramKind.SYSML2_UC
            is dev.kuml.sysml2.ReqDiagram -> SpecDiagramKind.SYSML2_REQ
            is dev.kuml.sysml2.StmDiagram -> SpecDiagramKind.SYSML2_STM
            is dev.kuml.sysml2.ActDiagram -> SpecDiagramKind.SYSML2_ACT
            is dev.kuml.sysml2.SeqDiagram -> SpecDiagramKind.SYSML2_SEQ
            is dev.kuml.sysml2.ParDiagram -> SpecDiagramKind.SYSML2_PAR
            is dev.kuml.sysml2.Sysml2PackageDiagram -> SpecDiagramKind.SYSML2_PKG
        }
}

/**
 * Which `layout { }` options each diagram art honours. Layered (ELK) pipelines honour all of them;
 * grid, time-line and fixed-geometry pipelines honour none and report `KUML-LAYOUT-W001` for every
 * option that was set. The handbook table `Keyword by diagram family` (layout.adoc) mirrors this object;
 * `LayoutEffectMatrixDocSyncTest` compares them, so change both together.
 */
public object LayoutEffectMatrix {
    private val all: Set<LayoutOption> =
        LayoutOption.entries.toSet() - LayoutOption.LABEL_MAX_WIDTH - LayoutOption.SHOW_SUBJECT_EDGES - LayoutOption.ACTOR_SIDE -
            LayoutOption.BOUNDARY_DIRECTION
    private val none: Set<LayoutOption> = emptySet()

    /** Options with an effect per diagram art; complete over [SpecDiagramKind.entries]. */
    public val effective: Map<SpecDiagramKind, Set<LayoutOption>> =
        mapOf(
            SpecDiagramKind.UML_CLASS to all,
            SpecDiagramKind.UML_OBJECT to all,
            SpecDiagramKind.UML_COMPOSITE_STRUCTURE to all,
            SpecDiagramKind.UML_PACKAGE to all,
            SpecDiagramKind.UML_DEPLOYMENT to all,
            SpecDiagramKind.UML_PROFILE to all,
            SpecDiagramKind.UML_COMPONENT to all,
            SpecDiagramKind.UML_USE_CASE to all,
            SpecDiagramKind.UML_STATE to all,
            SpecDiagramKind.UML_ACTIVITY to all,
            SpecDiagramKind.UML_COMMUNICATION to all,
            SpecDiagramKind.UML_INTERACTION_OVERVIEW to all,
            SpecDiagramKind.UML_SEQUENCE to none,
            SpecDiagramKind.UML_TIMING to none,
            SpecDiagramKind.SYSML2_BDD to all,
            SpecDiagramKind.SYSML2_IBD to all,
            SpecDiagramKind.SYSML2_STM to (all + LayoutOption.LABEL_MAX_WIDTH),
            SpecDiagramKind.SYSML2_REQ to (all + LayoutOption.SHOW_SUBJECT_EDGES),
            SpecDiagramKind.SYSML2_UC to (all + LayoutOption.ACTOR_SIDE),
            SpecDiagramKind.SYSML2_ACT to all,
            SpecDiagramKind.SYSML2_PAR to all,
            SpecDiagramKind.SYSML2_SEQ to none,
            SpecDiagramKind.SYSML2_PKG to all,
            SpecDiagramKind.C4 to (all + LayoutOption.BOUNDARY_DIRECTION),
            SpecDiagramKind.ERM to all,
            SpecDiagramKind.BPMN_PROCESS to all,
            SpecDiagramKind.BPMN_COLLABORATION to all,
            SpecDiagramKind.BPMN_CHOREOGRAPHY to none,
            SpecDiagramKind.BPMN_CONVERSATION to none,
            SpecDiagramKind.BLUEPRINT to none,
            SpecDiagramKind.JOURNEY to none,
        )

    /**
     * Options that [effective] lists for a kind but that have no effect on the elements drawn inside a compound
     * boundary (Nacharbeit 96, extended in Nacharbeit 97). The layered engine lays the boundary out as a compound
     * node and does not assign layers to its members, so `wrapColumns` and `sameLayer` act on the system context
     * diagram of C4 but not on the containers or components inside a boundary, nor on the members of a package, a
     * deployment node or a state-machine frame. `first`, `last`, `direction` and `aspectRatio` still act, and elements
     * outside the boundary stay movable. Measured per kind by `CompoundLayoutOptionsTest` (members' bounds identical
     * with and without the option). Kinds with their own grid inside the boundary (SysML 2 requirement groups, use-case
     * system boundary, activity lanes) are deliberately absent. Reported as `KUML-LAYOUT-W001` when the diagram has
     * compound members; not part of the handbook table cells (note 1 under the table explains it), so
     * `LayoutEffectMatrixDocSyncTest` ignores it. Since Nacharbeit 107 (extended in Nacharbeit 115) the kinds in
     * [honouredInSelfContainedCompound] are exceptions for self-contained compounds: there the options act on the members
     * of a package or deployment node unless a relationship crosses the border or the compound has nested groups.
     */
    public val ignoredInsideCompound: Map<SpecDiagramKind, Set<LayoutOption>> =
        run {
            val both = setOf(LayoutOption.WRAP_COLUMNS, LayoutOption.SAME_LAYER)
            mapOf(
                SpecDiagramKind.C4 to both,
                SpecDiagramKind.UML_CLASS to both,
                SpecDiagramKind.UML_PACKAGE to both,
                SpecDiagramKind.UML_COMPONENT to both,
                SpecDiagramKind.UML_DEPLOYMENT to both,
                SpecDiagramKind.UML_STATE to both,
                SpecDiagramKind.SYSML2_PKG to both,
            )
        }

    /**
     * Nacharbeit 107: options of [ignoredInsideCompound] that nevertheless act on the members of a compound group when
     * it is self-contained (no relationship crosses its border, no nested group). Nacharbeit 107 started with UML
     * package diagrams; Nacharbeit 115 extended it to UML class, component and deployment diagrams, which build their
     * packages and nodes as the same kind of compound group. Not part of the handbook table cells, so
     * `LayoutEffectMatrixDocSyncTest` ignores it. State frames, SysML 2 and C4 keep reporting W001.
     */
    public val honouredInSelfContainedCompound: Map<SpecDiagramKind, Set<LayoutOption>> =
        run {
            val both = setOf(LayoutOption.WRAP_COLUMNS, LayoutOption.SAME_LAYER)
            mapOf(
                SpecDiagramKind.UML_PACKAGE to both,
                SpecDiagramKind.UML_CLASS to both,
                SpecDiagramKind.UML_COMPONENT to both,
                SpecDiagramKind.UML_DEPLOYMENT to both,
            )
        }

    /** The options that have an effect on [kind]. */
    public fun effectiveFor(kind: SpecDiagramKind): Set<LayoutOption> = effective.getValue(kind)
}
