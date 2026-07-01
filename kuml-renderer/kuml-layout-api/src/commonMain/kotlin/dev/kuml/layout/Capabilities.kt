package dev.kuml.layout

import kotlinx.serialization.Serializable

/**
 * Maschinenlesbare Selbst-Deklaration einer Layout-Engine.
 *
 * Renderer und Clients können anhand dieser Capabilities entscheiden,
 * welche Engine für einen bestimmten Diagrammtyp und Stil geeignet ist.
 *
 * @property deterministic Wenn true: gleiche Eingabe + gleicher Seed ⇒ gleiches [LayoutResult].
 * @property supportedDiagramKinds Menge der Diagrammtypen, die diese Engine unterstützt.
 * @property supportedEdgeStyles Menge der nativ unterstützten Kanten-Routing-Stile.
 * @property respectsGridHints Wenn true: Engine berücksichtigt [NodeHints.gridCol]/[NodeHints.gridRow].
 * @property respectsRelativeConstraints Wenn true: Engine berücksichtigt [RelativeConstraint]-Listen.
 * @property maxRecommendedNodes Empfohlene Maximalgröße des Graphen; bei Überschreitung kann Qualität sinken.
 */
@Serializable
public data class LayoutCapabilities(
    val deterministic: Boolean,
    val supportedDiagramKinds: Set<DiagramKind>,
    val supportedEdgeStyles: Set<EdgeRouteStyle>,
    val respectsGridHints: Boolean,
    val respectsRelativeConstraints: Boolean,
    val maxRecommendedNodes: Int,
)

/**
 * Aufzählung der von kUML unterstützten Diagrammtypen.
 *
 * Engines deklarieren in [LayoutCapabilities.supportedDiagramKinds], welche Typen
 * sie sinnvoll layouten können. [Generic] dient als Fallback für unbekannte Typen.
 */
public enum class DiagramKind {
    UmlClass,
    UmlComponent,
    UmlUseCase,
    UmlState,
    UmlSequence,
    C4Context,
    C4Container,
    C4Component,
    C4Deployment,
    C4Dynamic,
    C4Landscape,
    Generic,
}
