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
        }
}

/**
 * Which `layout { }` options each diagram art honours. Layered (ELK) pipelines honour all of them;
 * grid, time-line and fixed-geometry pipelines honour none and report `KUML-LAYOUT-W001` for every
 * option that was set. The documentation table is checked against this object by a test.
 */
public object LayoutEffectMatrix {
    private val all: Set<LayoutOption> = LayoutOption.entries.toSet()
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
            SpecDiagramKind.SYSML2_STM to all,
            SpecDiagramKind.SYSML2_REQ to all,
            SpecDiagramKind.SYSML2_UC to all,
            SpecDiagramKind.SYSML2_ACT to all,
            SpecDiagramKind.SYSML2_PAR to all,
            SpecDiagramKind.SYSML2_SEQ to none,
            SpecDiagramKind.C4 to all,
            SpecDiagramKind.ERM to all,
            SpecDiagramKind.BPMN_PROCESS to all,
            SpecDiagramKind.BPMN_COLLABORATION to all,
            SpecDiagramKind.BPMN_CHOREOGRAPHY to none,
            SpecDiagramKind.BPMN_CONVERSATION to none,
            SpecDiagramKind.BLUEPRINT to none,
            SpecDiagramKind.JOURNEY to none,
        )

    /** The options that have an effect on [kind]. */
    public fun effectiveFor(kind: SpecDiagramKind): Set<LayoutOption> = effective.getValue(kind)
}
