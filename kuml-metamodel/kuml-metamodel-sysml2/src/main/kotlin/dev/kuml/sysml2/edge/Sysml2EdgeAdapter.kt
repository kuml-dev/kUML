package dev.kuml.sysml2.edge

import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.BindingConnectorUsage
import dev.kuml.sysml2.ControlFlowUsage
import dev.kuml.sysml2.ObjectFlowUsage
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.ReqContains
import dev.kuml.sysml2.ReqDerive
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.ReqSatisfy
import dev.kuml.sysml2.ReqVerify
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.UcAssociation
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UcExtend
import dev.kuml.sysml2.UcInclude

/**
 * Per-diagram-kind lookup that turns a layout edge id (the **string value**
 * of a `dev.kuml.layout.EdgeId`) into the metadata needed to render its
 * label, line style and arrow-head style.
 *
 * V2.0.13 closes the edge-label backlog that V2.0.7–V2.0.12 deferred —
 * every SysML 2 edge now reports its stereotype, guard / effect label, and
 * line / arrow style through this single interface so the SVG and LaTeX
 * renderers can dispatch consistently. Before V2.0.13 each SysML 2 edge
 * fell through the `EdgeRendererDispatcher`'s lookup-miss into a plain
 * solid line because the synthetic `KumlDiagram` hull holds no
 * `UmlRelationship` element for SysML 2 edges (transitions, flows, UC
 * associations, REQ traceability, parametric bindings).
 *
 * The interface uses the **raw string** edge id rather than the
 * `EdgeId` value class to avoid coupling `kuml-metamodel-sysml2` to
 * `kuml-renderer:kuml-layout-api`. Callsites in the renderers do
 * `edgeId.value` unwrapping.
 *
 * Implementations are pure-data-mapping; no Kotlin-SVG or Kotlin-LaTeX
 * symbols leak in. This is why the type lives in `kuml-metamodel-sysml2`
 * — both `kuml-io-svg` and `kuml-io-latex` already depend on this module
 * and can share the adapter without picking up a renderer-only dependency.
 *
 * Diagram-type-specific implementations follow in this file:
 *  - [UcEdgeAdapter] — Use Case Diagram (associations / includes / extends)
 *  - [ReqEdgeAdapter] — Requirement Diagram (satisfy / verify / derive / contains)
 *  - [StmEdgeAdapter] — State Transition Diagram (transitions on the model)
 *  - [ActEdgeAdapter] — Activity Diagram (ControlFlow + ObjectFlow on the model)
 *  - [ParEdgeAdapter] — Parametric Diagram (BindingConnector usages on the model)
 *
 * `metadataFor` returns `null` if the edge isn't owned by this adapter — the
 * default plain-line fallback in `EdgeRendererDispatcher` is used in that
 * case (legacy UML / C4 edges).
 */
public interface Sysml2EdgeAdapter {
    /**
     * Resolve metadata for an edge by its raw string id.
     *
     * @param edgeId The string value of a `dev.kuml.layout.EdgeId` —
     *   conventionally one of:
     *   - `assoc:<actorId>::<useCaseId>` (UC association),
     *   - `include:<src>::<tgt>` (UC include),
     *   - `extend:<src>::<tgt>` (UC extend),
     *   - `satisfy:<src>::<req>` / `verify:<src>::<req>` /
     *     `derive:<src>::<tgt>` / `contains:<parent>::<child>` (REQ),
     *   - the `TransitionUsage.id` (STM),
     *   - the `ControlFlowUsage.id` / `ObjectFlowUsage.id` (ACT),
     *   - the `BindingConnectorUsage.id` (PAR).
     * @return Metadata if this adapter owns the edge; `null` otherwise so
     *   the caller can fall back to the legacy plain-line path.
     */
    public fun metadataFor(edgeId: String): Sysml2EdgeMetadata?
}

/**
 * Render-instructions for a single SysML 2 edge.
 *
 * V2.0.13 keeps the shape minimal: a stereotype string, an optional plain
 * label, the SVG/TikZ dash-array, and a discriminator for the arrow-head
 * shape. Richer information — Bezier-midpoint labels, multi-line labels,
 * endpoint markers (open square on ObjectFlow), parameter-pin endpoint
 * anchoring — is scheduled for future V2.x polish.
 *
 * @property stereotype Optional `«…»`-stereotype label rendered as a
 *   small italic label above the line.
 * @property label Optional plain label. If [stereotype] is also set the
 *   plain label sits *below* the stereotype; if not, the plain label sits
 *   directly above the line. STM uses this slot for
 *   `trigger [guard] / effect`, ACT for `[guard]` (Control Flow) and
 *   `[ObjectType]` (Object Flow).
 * @property dashArray SVG `stroke-dasharray` / TikZ `dashed` discriminator.
 *   `null` means solid; e.g. `"5 4"` means dashed. UC `«include»` /
 *   `«extend»` and all four REQ kinds use dashed; STM / ACT / PAR are solid.
 * @property arrowHead Arrow-head shape at the target end. See
 *   [Sysml2ArrowHead].
 */
public data class Sysml2EdgeMetadata(
    val stereotype: String? = null,
    val label: String? = null,
    val dashArray: String? = null,
    val arrowHead: Sysml2ArrowHead = Sysml2ArrowHead.FilledTriangle,
)

/**
 * Arrow-head shapes that V2.0.13 distinguishes.
 *
 *  - [OpenTriangle] — open generalisation / specialisation triangle (not
 *    currently used by V2.0.13 but reserved for the V2.x BDD-specialisation
 *    polish wave).
 *  - [FilledTriangle] — the workhorse for directional edges (STM transitions,
 *    ACT control/object flows). SVG uses the existing `arrow-open` marker;
 *    TikZ uses `-{Stealth}`.
 *  - [OpenAngle] — V-shaped open angle (`>`-style) used for UC
 *    `«include»` / `«extend»` and all four REQ stereotypes.
 *  - [None] — no arrow head; used for UC associations (and PAR bindings
 *    today, although bindings are biderectional value flow — V2.x parametric
 *    solver may revisit).
 */
public enum class Sysml2ArrowHead {
    OpenTriangle,
    FilledTriangle,
    OpenAngle,
    None,
}

// ─── UC ──────────────────────────────────────────────────────────────────

/**
 * Use Case Diagram adapter (V2.0.13).
 *
 *  - [UcAssociation] → solid, no label, no arrow head — the classical
 *    actor-to-use-case line.
 *  - [UcInclude] → dashed `5 4`, `«include»` stereotype, `OpenAngle` arrow.
 *  - [UcExtend] → dashed `5 4`, `«extend»` stereotype, `OpenAngle` arrow.
 *
 * Lookup is by `UcAssociation.id` / `UcInclude.id` / `UcExtend.id`. The DSL
 * sets these to `assoc:<actorId>::<useCaseId>` / `include:<src>::<tgt>` /
 * `extend:<src>::<tgt>` respectively, but the adapter does not parse the
 * prefix — it simply indexes on the raw id, so callers that override the
 * id remain supported.
 */
public class UcEdgeAdapter(
    diagram: UcDiagram,
) : Sysml2EdgeAdapter {
    private val index: Map<String, Sysml2EdgeMetadata> =
        buildMap {
            for (assoc in diagram.associations) {
                put(assoc.id, METADATA_ASSOCIATION)
            }
            for (inc in diagram.includes) {
                put(inc.id, METADATA_INCLUDE)
            }
            for (ext in diagram.extends) {
                put(ext.id, METADATA_EXTEND)
            }
        }

    override fun metadataFor(edgeId: String): Sysml2EdgeMetadata? = index[edgeId]

    public companion object {
        public val METADATA_ASSOCIATION: Sysml2EdgeMetadata =
            Sysml2EdgeMetadata(arrowHead = Sysml2ArrowHead.None)
        public val METADATA_INCLUDE: Sysml2EdgeMetadata =
            Sysml2EdgeMetadata(
                stereotype = "«include»",
                dashArray = "5 4",
                arrowHead = Sysml2ArrowHead.OpenAngle,
            )
        public val METADATA_EXTEND: Sysml2EdgeMetadata =
            Sysml2EdgeMetadata(
                stereotype = "«extend»",
                dashArray = "5 4",
                arrowHead = Sysml2ArrowHead.OpenAngle,
            )
    }
}

// ─── REQ ─────────────────────────────────────────────────────────────────

/**
 * Requirement Diagram adapter (V2.0.13). All four edge kinds are dashed
 * with `OpenAngle` arrow heads and carry the canonical SysML 2 stereotype
 * label. The naming mirrors the SysML 2 spec rather than the metamodel
 * type names: `containment` (not `contains`), `deriveReqt` (not `derive`),
 * `satisfy`, `verify`.
 *
 *  - [ReqSatisfy] → `«satisfy»`
 *  - [ReqVerify] → `«verify»`
 *  - [ReqDerive] → `«deriveReqt»`
 *  - [ReqContains] → `«containment»`
 */
public class ReqEdgeAdapter(
    diagram: ReqDiagram,
) : Sysml2EdgeAdapter {
    private val index: Map<String, Sysml2EdgeMetadata> =
        buildMap {
            for (sat in diagram.satisfies) {
                put(sat.id, METADATA_SATISFY)
            }
            for (ver in diagram.verifies) {
                put(ver.id, METADATA_VERIFY)
            }
            for (der in diagram.derives) {
                put(der.id, METADATA_DERIVE)
            }
            for (con in diagram.contains) {
                put(con.id, METADATA_CONTAINS)
            }
        }

    override fun metadataFor(edgeId: String): Sysml2EdgeMetadata? = index[edgeId]

    public companion object {
        public val METADATA_SATISFY: Sysml2EdgeMetadata =
            Sysml2EdgeMetadata(
                stereotype = "«satisfy»",
                dashArray = "5 4",
                arrowHead = Sysml2ArrowHead.OpenAngle,
            )
        public val METADATA_VERIFY: Sysml2EdgeMetadata =
            Sysml2EdgeMetadata(
                stereotype = "«verify»",
                dashArray = "5 4",
                arrowHead = Sysml2ArrowHead.OpenAngle,
            )
        public val METADATA_DERIVE: Sysml2EdgeMetadata =
            Sysml2EdgeMetadata(
                stereotype = "«deriveReqt»",
                dashArray = "5 4",
                arrowHead = Sysml2ArrowHead.OpenAngle,
            )
        public val METADATA_CONTAINS: Sysml2EdgeMetadata =
            Sysml2EdgeMetadata(
                stereotype = "«containment»",
                dashArray = "5 4",
                arrowHead = Sysml2ArrowHead.OpenAngle,
            )
    }
}

// ─── STM ─────────────────────────────────────────────────────────────────

/**
 * State Transition Diagram adapter (V2.0.13).
 *
 * Iterates `model.usages.filterIsInstance<TransitionUsage>()` and keeps
 * the transitions whose source AND target states are visible (`elementIds`).
 * The plain label is the SysML 2 transition concrete syntax
 * `trigger [guard] / effect`, with omitted parts dropped — so a trigger-only
 * transition produces `trigger`, a guard-only produces `[guard]`, etc. If
 * all three slots are null the label is null and only the bare arrow renders.
 *
 * Lines are solid; arrow head is `FilledTriangle`. No stereotype label.
 */
public class StmEdgeAdapter(
    model: Sysml2Model,
    diagram: StmDiagram,
) : Sysml2EdgeAdapter {
    private val index: Map<String, Sysml2EdgeMetadata> =
        buildMap {
            val visible = diagram.elementIds.toSet()
            for (transition in model.usages.filterIsInstance<TransitionUsage>()) {
                if (transition.sourceStateId !in visible) continue
                if (transition.targetStateId !in visible) continue
                put(
                    transition.id,
                    Sysml2EdgeMetadata(
                        label = formatTransitionLabel(transition),
                        arrowHead = Sysml2ArrowHead.FilledTriangle,
                    ),
                )
            }
        }

    override fun metadataFor(edgeId: String): Sysml2EdgeMetadata? = index[edgeId]

    public companion object {
        /**
         * SysML 2 transition concrete syntax: `trigger [guard] / effect`. Each
         * slot is dropped if null/blank; if every slot is empty the result is
         * `null` so the renderer emits the bare arrow.
         */
        public fun formatTransitionLabel(transition: TransitionUsage): String? {
            val parts = mutableListOf<String>()
            transition.trigger?.takeIf { it.isNotBlank() }?.let { parts += it }
            transition.guard?.takeIf { it.isNotBlank() }?.let { parts += "[$it]" }
            transition.effect?.takeIf { it.isNotBlank() }?.let { parts += "/ $it" }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }
    }
}

// ─── ACT ─────────────────────────────────────────────────────────────────

/**
 * Activity Diagram adapter (V2.0.13).
 *
 * Iterates `model.usages.filterIsInstance<ControlFlowUsage>()` and
 * `…<ObjectFlowUsage>()` and keeps flows whose source AND target nodes are
 * visible (`elementIds`).
 *
 *  - [ControlFlowUsage] → solid, `FilledTriangle`, label = `[guard]` when
 *    guard is set, `null` otherwise.
 *  - [ObjectFlowUsage] → solid, `FilledTriangle`, label = `[objectType]`
 *    when objectType is set, `null` otherwise.
 *
 * The open-square ObjectFlow endpoint marker from the SysML 2 spec lands
 * in a future V2.x polish wave — see the wave-plan KDoc in
 * `ObjectFlowUsage`.
 */
public class ActEdgeAdapter(
    model: Sysml2Model,
    diagram: ActDiagram,
) : Sysml2EdgeAdapter {
    private val index: Map<String, Sysml2EdgeMetadata> =
        buildMap {
            val visible = diagram.elementIds.toSet()
            for (flow in model.usages.filterIsInstance<ControlFlowUsage>()) {
                if (flow.sourceNodeId !in visible) continue
                if (flow.targetNodeId !in visible) continue
                put(
                    flow.id,
                    Sysml2EdgeMetadata(
                        label = flow.guard?.takeIf { it.isNotBlank() }?.let { "[$it]" },
                        arrowHead = Sysml2ArrowHead.FilledTriangle,
                    ),
                )
            }
            for (flow in model.usages.filterIsInstance<ObjectFlowUsage>()) {
                if (flow.sourceNodeId !in visible) continue
                if (flow.targetNodeId !in visible) continue
                put(
                    flow.id,
                    Sysml2EdgeMetadata(
                        label = flow.objectType?.takeIf { it.isNotBlank() }?.let { "[$it]" },
                        arrowHead = Sysml2ArrowHead.FilledTriangle,
                    ),
                )
            }
        }

    override fun metadataFor(edgeId: String): Sysml2EdgeMetadata? = index[edgeId]
}

// ─── PAR ─────────────────────────────────────────────────────────────────

/**
 * Parametric Diagram adapter (V2.0.13).
 *
 * Iterates `model.usages.filterIsInstance<BindingConnectorUsage>()`. Every
 * binding is reported as a solid, label-less, arrow-head-less line —
 * minimal metadata to keep bindings rendering as solid edges (not the
 * unstyled fallback plain-edge path). This adapter exists for **symmetry**
 * with the other four diagram kinds and to allow the SVG / LaTeX renderers
 * to dispatch through one uniform code path.
 *
 * The SysML 2 spec puts no stereotype label on a bare binding (parameter
 * pins carry the labels), so the [Sysml2EdgeMetadata.stereotype] and
 * `label` slots stay null. Parameter-pin endpoint anchoring is V2.x polish.
 *
 * Endpoint visibility is **not** filtered here: the bridge already drops
 * dangling bindings via longest-prefix-match, so by the time the renderer
 * loop reaches us every edge in `layoutResult.edges` has a binding metadata
 * entry. The adapter therefore indexes *every* `BindingConnectorUsage` in
 * the model without checking visibility — visibility filtering is the
 * bridge's job, the adapter's job is metadata lookup.
 */
public class ParEdgeAdapter(
    model: Sysml2Model,
    @Suppress("unused_parameter") diagram: ParDiagram,
) : Sysml2EdgeAdapter {
    private val index: Map<String, Sysml2EdgeMetadata> =
        buildMap {
            for (binding in model.usages.filterIsInstance<BindingConnectorUsage>()) {
                put(binding.id, METADATA_BINDING)
            }
        }

    override fun metadataFor(edgeId: String): Sysml2EdgeMetadata? = index[edgeId]

    public companion object {
        public val METADATA_BINDING: Sysml2EdgeMetadata =
            Sysml2EdgeMetadata(arrowHead = Sysml2ArrowHead.None)
    }
}
