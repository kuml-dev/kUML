package dev.kuml.wasm.playground

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
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
import dev.kuml.uml.UmlSerializersModule
import kotlinx.serialization.json.Json
import kotlin.js.JsExport

/**
 * The `Json` instance used to decode diagrams and layouts posted to the
 * playground as JSON. Registers [UmlSerializersModule] so that the open,
 * polymorphic [KumlElement] / `KumlNamespaceMember` bases can resolve their
 * concrete UML subtypes at decode time (see `UmlSerializersModule` KDoc in
 * `kuml-metamodel-uml`).
 *
 * `classDiscriminator` is set to `"@type"` instead of the kotlinx default
 * `"type"`, because [KumlDiagram] already has a real field named `type`
 * ([DiagramType]) — using the default discriminator key would collide with
 * it during polymorphic (de)serialization of nested elements.
 *
 * Scope: **UML only** this wave. C4/BPMN/SysML2/KerML/Blueprint diagrams
 * are not decodable via this `Json` instance yet (registering their
 * `SerializersModule`s is the natural V3.2.11 follow-up).
 */
private val playgroundJson =
    Json {
        serializersModule = UmlSerializersModule
        ignoreUnknownKeys = true
        classDiscriminator = "@type"
    }

/**
 * V3.2.9/V3.2.10 wasmJs render entry points.
 *
 * What this genuinely does:
 *  - [renderSampleClassDiagram]: build a small, hand-rolled [KumlDiagram] (two
 *    [UmlClass]es + one [UmlAssociation]) directly in Kotlin, lay it out with
 *    a trivial single-row grid (no ELK — ELK is JVM-only), and render it to
 *    SVG via [KumlSvgRenderer.toSvg], the same commonMain renderer used by
 *    the JVM CLI.
 *  - [renderDiagramJson]: decode an arbitrary UML [KumlDiagram] and a
 *    precomputed [LayoutResult], both supplied as JSON, and render to SVG.
 *    This is the real V3.2.10 unblock: [KumlDiagram] and the [KumlElement] /
 *    `KumlNamespaceMember` bases are `@Serializable`/`@Polymorphic`, and the
 *    UML metamodel's concrete subtypes are registered via
 *    [UmlSerializersModule].
 *  - [renderDiagramJsonWithGrid]: convenience wrapper around
 *    [renderDiagramJson] that computes layout with the same demo-only grid
 *    scaffold used by [renderSampleClassDiagram], for callers that only
 *    have a diagram and no layout. NOT a general-purpose layout engine —
 *    single row, fixed box sizes, no collision avoidance, no edge routing
 *    beyond straight lines.
 *
 * What this explicitly does NOT do:
 *  - Parse arbitrary `.kuml.kts` DSL source. Kotlin scripting
 *    (`kuml-core-script`) has no Kotlin/Wasm backend.
 *  - Compute a *real* layout for an arbitrary [KumlDiagram]. The only
 *    production layout engine (`kuml-layout-elk`) wraps the JVM-only
 *    Eclipse ELK library; `LayoutEngineRegistry`'s wasmJs actual returns an
 *    empty provider list. [renderDiagramJsonWithGrid]'s grid is a demo
 *    convenience only, not a replacement.
 *  - Decode non-UML diagrams (C4/BPMN/SysML2/KerML/Blueprint elements are
 *    `@Serializable` but not registered in [playgroundJson] yet).
 *
 * See CLAUDE.md kUML section "V3.2.10 Plan" for the full design rationale.
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

/**
 * Decodes a [KumlDiagram] and a precomputed [LayoutResult] from JSON and
 * renders them to SVG via [KumlSvgRenderer.toSvg].
 *
 * Both JSON payloads must use `"@type"` as the polymorphic class
 * discriminator (not the kotlinx default `"type"`) — see [playgroundJson]
 * KDoc. Only UML elements are decodable; other metamodels throw
 * `SerializationException` because their subtypes are not registered in
 * [UmlSerializersModule].
 *
 * @param diagramJson JSON encoding of a [KumlDiagram] whose `elements` are
 *   drawn from the UML metamodel (`dev.kuml.uml.*`).
 * @param layoutJson JSON encoding of a [LayoutResult] with node/edge
 *   placements for every element `id` in [diagramJson].
 * @return the rendered SVG document as a string.
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public fun renderDiagramJson(
    diagramJson: String,
    layoutJson: String,
): String {
    val diagram = playgroundJson.decodeFromString(KumlDiagram.serializer(), diagramJson)
    val layoutResult = playgroundJson.decodeFromString(LayoutResult.serializer(), layoutJson)
    return KumlSvgRenderer.toSvg(
        diagram = diagram,
        layoutResult = layoutResult,
        theme = PlainTheme(),
        options = SvgRenderOptions.DEFAULT,
    )
}

/**
 * Convenience wrapper around [renderDiagramJson] for callers that only have
 * a diagram and no precomputed layout. Computes a trivial single-row grid
 * layout over the diagram's top-level elements — fixed box sizes, fixed
 * spacing, no collision avoidance, no real edge routing.
 *
 * This is a demo-only placeholder, NOT a general-purpose layout engine.
 * wasm has no real layout engine available (`kuml-layout-elk` is JVM-only;
 * see the module KDoc above). Prefer [renderDiagramJson] with a real
 * `LayoutResult` (e.g. computed on the JVM side and shipped as JSON) for
 * anything beyond a quick demo.
 *
 * @param diagramJson JSON encoding of a [KumlDiagram] (UML elements only).
 * @return the rendered SVG document as a string.
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public fun renderDiagramJsonWithGrid(diagramJson: String): String {
    val diagram = playgroundJson.decodeFromString(KumlDiagram.serializer(), diagramJson)

    val boxWidth = 160f
    val boxHeight = 80f
    val gap = 80f
    val margin = 40f

    val nodeIds = diagram.elements.map { it.id }
    val nodes =
        nodeIds.mapIndexed { index, id ->
            val x = margin + index * (boxWidth + gap)
            NodeId(id) to
                NodeLayout(
                    bounds = Rect(origin = Point(x = x, y = margin), size = Size(boxWidth, boxHeight)),
                )
        }.toMap()

    val canvasWidth =
        if (nodeIds.isEmpty()) {
            margin * 2
        } else {
            margin + nodeIds.size * boxWidth + (nodeIds.size - 1) * gap + margin
        }
    val canvasHeight = margin + boxHeight + margin

    val layoutResult =
        LayoutResult(
            engineId = LayoutEngineId("wasm-grid-scaffold"),
            seed = null,
            canvas = Size(canvasWidth, canvasHeight),
            nodes = nodes,
            edges = emptyMap(),
            groups = emptyMap(),
        )

    return KumlSvgRenderer.toSvg(
        diagram = diagram,
        layoutResult = layoutResult,
        theme = PlainTheme(),
        options = SvgRenderOptions.DEFAULT,
    )
}
