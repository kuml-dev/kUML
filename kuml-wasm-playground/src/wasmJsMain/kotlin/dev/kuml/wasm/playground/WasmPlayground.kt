package dev.kuml.wasm.playground

import dev.kuml.blueprint.model.BlueprintDiagram
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.bpmn.model.BpmnDiagram
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.ConversationDiagram
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.c4.model.C4Diagram
import dev.kuml.c4.model.C4Model
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.UmlContentSizeProvider
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.grid.GridLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.UcDiagram
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlSerializersModule
import kotlinx.serialization.json.Json
import kotlin.js.JsExport

/**
 * Hard cap on the byte length of any JSON payload accepted by the playground
 * entry points below.
 *
 * The playground decodes **untrusted** JSON straight from a browser text area,
 * so an attacker (or an accidental multi-megabyte paste) could otherwise force
 * the decoder to allocate an unbounded object graph. kotlinx.serialization has
 * no built-in size/depth limit, so we guard the raw input length before the
 * decoder ever sees it. 8 MiB is comfortably above any legitimate hand-written
 * or `kuml dump-json`-produced diagram while still bounding worst-case memory.
 *
 * This is a *coarse* guard (input character length, not decoded object count),
 * but combined with `ignoreUnknownKeys = true` (which drops rather than
 * expands unknown fields) it removes the most obvious billion-laughs / giant
 * payload amplification vectors. The models decoded here are plain data trees
 * (no back-references, no recursive `@Polymorphic` self-nesting beyond the
 * sealed diagram hierarchies), so decoding is linear in the input size.
 */
private const val MAX_JSON_PAYLOAD_BYTES: Int = 8 * 1024 * 1024

/**
 * Throws [IllegalArgumentException] if [json] exceeds [MAX_JSON_PAYLOAD_BYTES].
 *
 * Uses UTF-8 byte length (not `String.length`) so multi-byte characters cannot
 * be used to slip past the cap.
 */
private fun requireWithinSizeLimit(
    json: String,
    label: String,
) {
    val bytes = json.encodeToByteArray().size
    require(bytes <= MAX_JSON_PAYLOAD_BYTES) {
        "$label JSON payload is $bytes bytes, exceeding the ${MAX_JSON_PAYLOAD_BYTES}-byte playground limit."
    }
}

/**
 * The `Json` instance used to decode **UML** diagrams. Registers
 * [UmlSerializersModule] so that the open, polymorphic [KumlElement] /
 * `KumlNamespaceMember` bases can resolve their concrete UML subtypes at
 * decode time (see `UmlSerializersModule` KDoc in `kuml-metamodel-uml`).
 *
 * `classDiscriminator` is set to `"@type"` instead of the kotlinx default
 * `"type"`, because [KumlDiagram] already has a real field named `type`
 * ([DiagramType]) — using the default discriminator key would collide with
 * it during polymorphic (de)serialization of nested elements.
 */
private val umlJson =
    Json {
        serializersModule = UmlSerializersModule
        ignoreUnknownKeys = true
        classDiscriminator = "@type"
    }

/**
 * The `Json` instance used to decode **C4 / SysML 2 / BPMN / Blueprint**
 * models and diagrams.
 *
 * Unlike UML — whose `KumlDiagram.elements: List<KumlElement>` decodes through
 * the *open* [KumlElement] polymorphic base and therefore needs an explicit
 * [UmlSerializersModule] — the other metamodels expose their content through
 * `sealed` `@Serializable` hierarchies (`C4Model`/`C4Element`,
 * `Sysml2Model`/`Sysml2Diagram`, `BpmnModel`/`BpmnDiagram`,
 * `BlueprintModel`/`BlueprintDiagram`). kotlinx.serialization auto-derives a
 * polymorphic serializer for a sealed base, so decoding a concrete model type
 * needs **no** custom `SerializersModule`.
 *
 * `classDiscriminator` is still `"@type"` for consistency with [umlJson] and
 * because these sealed hierarchies also embed a `KumlDiagram`-style `type`
 * field in some subtypes; keeping a non-`"type"` discriminator avoids any
 * collision.
 */
private val sealedJson =
    Json {
        ignoreUnknownKeys = true
        classDiscriminator = "@type"
    }

// ─────────────────────────────────────────────────────────────────────────────
// Module KDoc
// ─────────────────────────────────────────────────────────────────────────────

/**
 * wasmJs render entry points for the browser-hosted kUML playground.
 *
 * **What this genuinely does:**
 *  - [renderSampleClassDiagram]: build a small, hand-rolled UML [KumlDiagram]
 *    directly in Kotlin, lay it out with the real [GridLayoutEngine], and
 *    render to SVG via [KumlSvgRenderer.toSvg] (the same commonMain renderer
 *    used by the JVM CLI).
 *  - [renderDiagramJson]: decode an arbitrary UML [KumlDiagram] + precomputed
 *    [LayoutResult] (both JSON) and render to SVG.
 *  - [renderDiagramJsonWithGrid]: decode a UML [KumlDiagram] and compute a
 *    **real** multi-column, content-sized grid layout in-wasm via the
 *    multiplatform [UmlLayoutBridge] + [GridLayoutEngine]. This is a genuine
 *    layout (multi-row/column slot allocation, content-based node sizing, edge
 *    routing), not the single-row demo scaffold it replaced. It is still not
 *    ELK — no crossing minimisation or layered ranking — but it is a real
 *    fallback for callers that have a diagram and no precomputed layout.
 *  - [renderC4DiagramJson], [renderSysml2DiagramJson], [renderBpmnDiagramJson]:
 *    decode a concrete metamodel `Model` + `Diagram` + precomputed
 *    [LayoutResult] (all JSON) and render via the matching `KumlSvgRenderer`
 *    overload. Layout is supplied by the caller (typically produced on the JVM
 *    by `kuml dump-json`) because C4/SysML2/BPMN are laid out with ELK on the
 *    JVM and grid is a weaker fallback for them.
 *  - [renderBlueprintDiagramJson]: decode a [BlueprintModel] + [BlueprintDiagram]
 *    and render. Blueprint is geometry-driven and needs no [LayoutResult] at all.
 *
 * **What this explicitly does NOT do (honest scope):**
 *  - Parse arbitrary `.kuml.kts` DSL source. Kotlin scripting
 *    (`kuml-core-script`) has no Kotlin/Wasm backend, so DSL evaluation stays
 *    JVM-only. Feed pre-decoded `KumlDiagram`/model JSON instead (e.g. from
 *    `kuml dump-json`).
 *  - Run the ELK layout engine in wasm. `kuml-layout-elk` wraps the JVM-only
 *    Eclipse ELK Java library; `LayoutEngineRegistry.loadProvidersFromClasspath()`
 *    is a no-op on wasmJs. The multiplatform [GridLayoutEngine] is available and
 *    used for the UML `*WithGrid` path, but it is a simpler algorithm than ELK.
 *    For C4/SysML2/BPMN, layout must be precomputed (JVM) and shipped as JSON.
 *  - Render KerML diagrams. There is no `KumlSvgRenderer` overload for KerML in
 *    `kuml-io-svg` — KerML currently has no SVG renderer on any platform, so it
 *    is out of scope here regardless of serialization. This is a genuine gap,
 *    not a wasm-specific limitation.
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

    val layoutResult = layoutUmlWithGrid(diagram)

    return KumlSvgRenderer.toSvg(
        diagram = diagram,
        layoutResult = layoutResult,
        theme = PlainTheme(),
        options = SvgRenderOptions.DEFAULT,
    )
}

/**
 * Decodes a UML [KumlDiagram] and a precomputed [LayoutResult] from JSON and
 * renders them to SVG via [KumlSvgRenderer.toSvg].
 *
 * Both JSON payloads must use `"@type"` as the polymorphic class discriminator
 * (see [umlJson] KDoc). Only UML elements are decodable through this path;
 * non-UML metamodels have their own entry points ([renderC4DiagramJson] etc.)
 * because they render from a `Model` + `Diagram` pair, not a `KumlDiagram`.
 *
 * @param diagramJson JSON encoding of a UML [KumlDiagram].
 * @param layoutJson JSON encoding of a [LayoutResult].
 * @return the rendered SVG document as a string.
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public fun renderDiagramJson(
    diagramJson: String,
    layoutJson: String,
): String {
    requireWithinSizeLimit(diagramJson, "diagram")
    requireWithinSizeLimit(layoutJson, "layout")
    val diagram = umlJson.decodeFromString(KumlDiagram.serializer(), diagramJson)
    val layoutResult = umlJson.decodeFromString(LayoutResult.serializer(), layoutJson)
    return KumlSvgRenderer.toSvg(
        diagram = diagram,
        layoutResult = layoutResult,
        theme = PlainTheme(),
        options = SvgRenderOptions.DEFAULT,
    )
}

/**
 * Decodes a UML [KumlDiagram] and computes a **real** grid layout in-wasm via
 * the multiplatform [UmlLayoutBridge] + [GridLayoutEngine], then renders to
 * SVG. For callers that have a diagram but no precomputed [LayoutResult].
 *
 * The layout uses content-aware node sizing ([UmlContentSizeProvider]) and the
 * grid engine's multi-row/column slot allocation and edge routing — a genuine
 * improvement over the old single-row demo scaffold. It is not ELK-quality for
 * dense graphs (no crossing minimisation), so prefer [renderDiagramJson] with a
 * precomputed layout for complex diagrams.
 *
 * @param diagramJson JSON encoding of a UML [KumlDiagram].
 * @return the rendered SVG document as a string.
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public fun renderDiagramJsonWithGrid(diagramJson: String): String {
    requireWithinSizeLimit(diagramJson, "diagram")
    val diagram = umlJson.decodeFromString(KumlDiagram.serializer(), diagramJson)
    val layoutResult = layoutUmlWithGrid(diagram)
    return KumlSvgRenderer.toSvg(
        diagram = diagram,
        layoutResult = layoutResult,
        theme = PlainTheme(),
        options = SvgRenderOptions.DEFAULT,
    )
}

/**
 * Runs the multiplatform UML layout bridge + grid engine over [diagram].
 *
 * Mirrors the JVM `RenderPipeline` UML path (`UmlLayoutBridge.toLayoutGraph` +
 * `UmlContentSizeProvider`) but uses the pure-Kotlin [GridLayoutEngine] instead
 * of ELK (ELK is JVM-only). Falls back to an empty single-node canvas when the
 * diagram has no layoutable elements.
 */
private fun layoutUmlWithGrid(diagram: KumlDiagram): LayoutResult {
    val hints = LayoutHints.DEFAULT
    val graph =
        UmlLayoutBridge.toLayoutGraph(
            diagram,
            UmlContentSizeProvider(diagram, hints.direction),
        )
    if (graph.nodes.isEmpty()) {
        // Nothing to lay out — return an empty canvas so the renderer still
        // produces a valid (empty) SVG rather than throwing.
        return LayoutResult(
            engineId = LayoutEngineId("kuml.grid"),
            seed = null,
            canvas = Size(width = 80f, height = 80f),
            nodes = emptyMap<NodeId, NodeLayout>(),
            edges = emptyMap<EdgeId, EdgeRoute>(),
            groups = emptyMap(),
        )
    }
    return GridLayoutEngine().layout(graph, hints)
}

/**
 * Decodes a [C4Model] + [C4Diagram] + precomputed [LayoutResult] and renders
 * to SVG via `KumlSvgRenderer.toSvg(C4Diagram, C4Model, LayoutResult)`.
 *
 * The C4 renderer takes the diagram and its owning model (for element lookup)
 * plus a layout. Grid layout for C4 compound (container/component) diagrams is
 * possible in principle but weaker than the ELK path used on the JVM, so the
 * layout is supplied by the caller (e.g. `kuml dump-json`).
 *
 * @param modelJson JSON encoding of a [C4Model].
 * @param diagramJson JSON encoding of a [C4Diagram] (one of the sealed subtypes).
 * @param layoutJson JSON encoding of a [LayoutResult].
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public fun renderC4DiagramJson(
    modelJson: String,
    diagramJson: String,
    layoutJson: String,
): String {
    requireWithinSizeLimit(modelJson, "C4 model")
    requireWithinSizeLimit(diagramJson, "C4 diagram")
    requireWithinSizeLimit(layoutJson, "layout")
    val model = sealedJson.decodeFromString(C4Model.serializer(), modelJson)
    val diagram = sealedJson.decodeFromString(C4Diagram.serializer(), diagramJson)
    val layoutResult = sealedJson.decodeFromString(LayoutResult.serializer(), layoutJson)
    return KumlSvgRenderer.toSvg(
        diagram = diagram,
        model = model,
        layoutResult = layoutResult,
        theme = PlainTheme(),
        options = SvgRenderOptions.DEFAULT,
    )
}

/**
 * Decodes a [Sysml2Model] + [Sysml2Diagram] + precomputed [LayoutResult] and
 * renders to SVG. Dispatches on the concrete sealed [Sysml2Diagram] subtype
 * (BDD / IBD / UC / REQ / STM / ACT / SEQ / PAR) to the matching renderer
 * overload — the SVG renderer has no umbrella `Sysml2Diagram` overload.
 *
 * @param modelJson JSON encoding of a [Sysml2Model].
 * @param diagramJson JSON encoding of a [Sysml2Diagram] subtype.
 * @param layoutJson JSON encoding of a [LayoutResult].
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public fun renderSysml2DiagramJson(
    modelJson: String,
    diagramJson: String,
    layoutJson: String,
): String {
    requireWithinSizeLimit(modelJson, "SysML 2 model")
    requireWithinSizeLimit(diagramJson, "SysML 2 diagram")
    requireWithinSizeLimit(layoutJson, "layout")
    val model = sealedJson.decodeFromString(Sysml2Model.serializer(), modelJson)
    val diagram = sealedJson.decodeFromString(Sysml2Diagram.serializer(), diagramJson)
    val layoutResult = sealedJson.decodeFromString(LayoutResult.serializer(), layoutJson)
    val theme = PlainTheme()
    val options = SvgRenderOptions.DEFAULT
    return when (diagram) {
        is BdDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, options)
        is IbdDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, options)
        is UcDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, options)
        is ReqDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, options)
        is StmDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, options)
        is ActDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, options)
        is SeqDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, options)
        is ParDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, options)
    }
}

/**
 * Decodes a [BpmnModel] + [BpmnDiagram] + precomputed [LayoutResult] and
 * renders to SVG. Dispatches on the concrete sealed [BpmnDiagram] subtype.
 *
 * A [ProcessDiagram] renders through the UML-style renderer over a synthetic
 * [KumlDiagram] view (built from `process.renderableElements()`, mirroring the
 * JVM `RenderPipeline`); the other three subtypes render via their
 * `KumlSvgRenderer.toSvg(model, diagram, layout)` overloads.
 *
 * @param modelJson JSON encoding of a [BpmnModel].
 * @param diagramJson JSON encoding of a [BpmnDiagram] subtype.
 * @param layoutJson JSON encoding of a [LayoutResult].
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public fun renderBpmnDiagramJson(
    modelJson: String,
    diagramJson: String,
    layoutJson: String,
): String {
    requireWithinSizeLimit(modelJson, "BPMN model")
    requireWithinSizeLimit(diagramJson, "BPMN diagram")
    requireWithinSizeLimit(layoutJson, "layout")
    val model = sealedJson.decodeFromString(BpmnModel.serializer(), modelJson)
    val diagram = sealedJson.decodeFromString(BpmnDiagram.serializer(), diagramJson)
    val layoutResult = sealedJson.decodeFromString(LayoutResult.serializer(), layoutJson)
    val theme = PlainTheme()
    val options = SvgRenderOptions.DEFAULT
    return when (diagram) {
        is ProcessDiagram -> {
            val process = model.processes.firstOrNull { it.id == diagram.processId }
            val elements: List<KumlElement> = process?.renderableElements() ?: emptyList()
            val kumlDiagram =
                KumlDiagram(
                    name = diagram.name,
                    type = DiagramType.BPMN_PROCESS,
                    elements = elements,
                )
            KumlSvgRenderer.toSvg(kumlDiagram, layoutResult, theme, options)
        }
        is CollaborationDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, options)
        is ChoreographyDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, options)
        is ConversationDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, options)
    }
}

/**
 * Decodes a [BlueprintModel] + [BlueprintDiagram] and renders to SVG.
 *
 * Blueprint / Journey-Map diagrams are geometry-driven: the renderer computes
 * placement from the model's phases/layers directly, so **no** [LayoutResult]
 * is needed (unlike every other diagram type).
 *
 * @param modelJson JSON encoding of a [BlueprintModel].
 * @param diagramJson JSON encoding of a [BlueprintDiagram].
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public fun renderBlueprintDiagramJson(
    modelJson: String,
    diagramJson: String,
): String {
    requireWithinSizeLimit(modelJson, "Blueprint model")
    requireWithinSizeLimit(diagramJson, "Blueprint diagram")
    val model = sealedJson.decodeFromString(BlueprintModel.serializer(), modelJson)
    val diagram = sealedJson.decodeFromString(BlueprintDiagram.serializer(), diagramJson)
    return KumlSvgRenderer.toSvg(model, diagram, PlainTheme())
}
