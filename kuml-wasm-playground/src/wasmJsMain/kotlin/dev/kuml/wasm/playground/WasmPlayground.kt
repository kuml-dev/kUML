package dev.kuml.wasm.playground

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import kotlin.js.JsExport

/**
 * V3.2.9 wasmJs render entry point — SCAFFOLDING ONLY.
 *
 * What this genuinely does: build a small, hand-rolled [KumlDiagram] (two
 * [UmlClass]es + one [UmlAssociation]) directly in Kotlin, lay it out with a
 * trivial single-row grid (no ELK — ELK is JVM-only), and render it to SVG via
 * [KumlSvgRenderer.toSvg], the same commonMain renderer used by the JVM CLI.
 *
 * What this explicitly does NOT do:
 *  - Parse arbitrary `.kuml.kts` DSL source. Kotlin scripting
 *    (`kuml-core-script`) has no Kotlin/Wasm backend.
 *  - Compute a layout for an arbitrary [KumlDiagram]. The only production
 *    layout engine (`kuml-layout-elk`) wraps the JVM-only Eclipse ELK
 *    library; `LayoutEngineRegistry`'s wasmJs actual returns an empty
 *    provider list.
 *  - Accept a diagram as JSON. [KumlDiagram]/[dev.kuml.core.model.KumlElement]
 *    are not `@Serializable` today (only individual metamodel elements like
 *    [UmlClass] are).
 *
 * These three gaps are tracked as the V3.2.10 follow-up (see CLAUDE.md
 * kUML section "Follow-up unblockers").
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public fun renderSampleClassDiagram(): String {
    val classA =
        UmlClass(
            id = "A",
            name = "Order",
        )
    val classB =
        UmlClass(
            id = "B",
            name = "Customer",
        )
    val association =
        UmlAssociation(
            id = "assoc-1",
            ends =
                listOf(
                    UmlAssociationEnd(typeId = "A", role = "orders", multiplicity = Multiplicity(lower = 0, upper = null)),
                    UmlAssociationEnd(typeId = "B", role = "customer", multiplicity = Multiplicity(lower = 1, upper = 1)),
                ),
            aggregation = AggregationKind.NONE,
        )

    val diagram =
        KumlDiagram(
            name = "WasmPlaygroundSample",
            type = DiagramType.CLASS,
            elements = listOf(classA, classB, association),
        )

    // Trivial single-row grid layout — deliberately NOT a general-purpose
    // layout engine. Fixed box sizes, fixed spacing, no collision avoidance
    // beyond simple horizontal placement, no edge routing beyond a straight
    // line between box centers. This exists only to unblock the wasm render
    // demo; it must not be mistaken for a real ELK replacement.
    val boxWidth = 160f
    val boxHeight = 80f
    val gap = 80f

    val nodeA =
        NodeLayout(
            bounds = Rect(origin = Point(x = 40f, y = 40f), size = Size(boxWidth, boxHeight)),
        )
    val nodeB =
        NodeLayout(
            bounds =
                Rect(
                    origin = Point(x = 40f + boxWidth + gap, y = 40f),
                    size = Size(boxWidth, boxHeight),
                ),
        )

    val edgeRoute =
        EdgeRoute.Direct(
            source = Point(x = 40f + boxWidth, y = 40f + boxHeight / 2f),
            target = Point(x = 40f + boxWidth + gap, y = 40f + boxHeight / 2f),
        )

    val canvasWidth = 40f + boxWidth + gap + boxWidth + 40f
    val canvasHeight = 40f + boxHeight + 40f

    val layoutResult =
        LayoutResult(
            engineId = LayoutEngineId("wasm-grid-scaffold"),
            seed = null,
            canvas = Size(canvasWidth, canvasHeight),
            nodes = mapOf(NodeId("A") to nodeA, NodeId("B") to nodeB),
            edges = mapOf(EdgeId("assoc-1") to edgeRoute),
            groups = emptyMap(),
        )

    return KumlSvgRenderer.toSvg(
        diagram = diagram,
        layoutResult = layoutResult,
        theme = PlainTheme(),
        options = SvgRenderOptions.DEFAULT,
    )
}
