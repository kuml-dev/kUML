package dev.kuml.io.latex.sysml2

import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.sysml2.edge.Sysml2EdgeLatexRenderer
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActivityNodeKind
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.BindingConnectorUsage
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.ControlFlowUsage
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.ReqDerive
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.ReqSatisfy
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.UcAssociation
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UcExtend
import dev.kuml.sysml2.UcInclude
import dev.kuml.sysml2.UseCaseDefinition
import dev.kuml.sysml2.edge.Sysml2EdgeMetadata
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * V2.0.13 — one smoke test per SysML 2 diagram kind, confirming the
 * stereotype / label text appears in the rendered TikZ output and the
 * new dashed-edge style is referenced.
 *
 * Per-stereotype expectations:
 *  - UC → `\guillemotleft{}include\guillemotright{}` /
 *    `\guillemotleft{}extend\guillemotright{}` (after LaTeX escaping).
 *  - REQ → `\guillemotleft{}satisfy\guillemotright{}` and
 *    `\guillemotleft{}deriveReqt\guillemotright{}`.
 *  - STM → `powerOn [battery \textgreater\ 0] / boot()` body shows up
 *    verbatim apart from the `>` (which is safe in TikZ text but the
 *    test asserts on the trigger word for robustness).
 *  - ACT → `[ready]` / `[Order]` labels.
 *  - PAR → no label assertion; the test just confirms the picture renders
 *    and contains the `kuml-sysml2-edge-binding` style on the edges.
 */
class Sysml2EdgeLabelLatexTest :
    StringSpec({

        // ─── UC ───────────────────────────────────────────────────────────

        "UC TikZ contains «include» and «extend» stereotype labels" {
            val model =
                Sysml2Model(
                    name = "Library",
                    definitions =
                        listOf(
                            ActorDefinition(id = "Reader", name = "Reader"),
                            UseCaseDefinition(id = "BorrowBook", name = "BorrowBook"),
                            UseCaseDefinition(id = "Authenticate", name = "Authenticate"),
                            UseCaseDefinition(id = "PayLateFee", name = "PayLateFee"),
                        ),
                )
            val diagram =
                UcDiagram(
                    name = "UC",
                    elementIds = listOf("Reader", "BorrowBook", "Authenticate", "PayLateFee"),
                    associations =
                        listOf(
                            UcAssociation(
                                id = "assoc:Reader::BorrowBook",
                                actorId = "Reader",
                                useCaseId = "BorrowBook",
                            ),
                        ),
                    includes =
                        listOf(
                            UcInclude(
                                id = "include:BorrowBook::Authenticate",
                                sourceUseCaseId = "BorrowBook",
                                targetUseCaseId = "Authenticate",
                            ),
                        ),
                    extends =
                        listOf(
                            UcExtend(
                                id = "extend:PayLateFee::BorrowBook",
                                sourceUseCaseId = "PayLateFee",
                                targetUseCaseId = "BorrowBook",
                            ),
                        ),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 800f, height = 220f),
                    nodes =
                        mapOf(
                            NodeId("Reader") to
                                NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 60f), size = Size(width = 60f, height = 100f))),
                            NodeId("BorrowBook") to
                                NodeLayout(bounds = Rect(origin = Point(x = 160f, y = 30f), size = Size(width = 160f, height = 70f))),
                            NodeId("Authenticate") to
                                NodeLayout(bounds = Rect(origin = Point(x = 420f, y = 30f), size = Size(width = 160f, height = 70f))),
                            NodeId("PayLateFee") to
                                NodeLayout(bounds = Rect(origin = Point(x = 160f, y = 130f), size = Size(width = 160f, height = 70f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("assoc:Reader::BorrowBook") to
                                EdgeRoute.Direct(source = Point(x = 80f, y = 110f), target = Point(x = 160f, y = 65f)),
                            EdgeId("include:BorrowBook::Authenticate") to
                                EdgeRoute.Direct(source = Point(x = 320f, y = 65f), target = Point(x = 420f, y = 65f)),
                            EdgeId("extend:PayLateFee::BorrowBook") to
                                EdgeRoute.Direct(source = Point(x = 240f, y = 130f), target = Point(x = 240f, y = 100f)),
                        ),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(model = model, diagram = diagram, layoutResult = layout)
            tex shouldContain "\\guillemotleft{}include\\guillemotright{}"
            tex shouldContain "\\guillemotleft{}extend\\guillemotright{}"
            tex shouldContain "kuml-sysml2-edge-dashed"
        }

        // ─── REQ ──────────────────────────────────────────────────────────

        "REQ TikZ contains «satisfy» and «deriveReqt» stereotype labels" {
            val model =
                Sysml2Model(
                    name = "Reqs",
                    definitions =
                        listOf(
                            PartDefinition(id = "Vehicle", name = "Vehicle"),
                            RequirementDefinition(id = "TopSpeedReq", name = "TopSpeedReq"),
                            RequirementDefinition(id = "SafetyReq", name = "SafetyReq"),
                            RequirementDefinition(id = "BrakingReq", name = "BrakingReq"),
                        ),
                )
            val diagram =
                ReqDiagram(
                    name = "Reqs",
                    elementIds = listOf("Vehicle", "TopSpeedReq", "SafetyReq", "BrakingReq"),
                    satisfies =
                        listOf(
                            ReqSatisfy(
                                id = "satisfy:Vehicle::TopSpeedReq",
                                sourceId = "Vehicle",
                                requirementId = "TopSpeedReq",
                            ),
                        ),
                    derives =
                        listOf(
                            ReqDerive(
                                id = "derive:BrakingReq::SafetyReq",
                                sourceRequirementId = "BrakingReq",
                                targetRequirementId = "SafetyReq",
                            ),
                        ),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 900f, height = 400f),
                    nodes =
                        mapOf(
                            NodeId("Vehicle") to
                                NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 40f), size = Size(width = 120f, height = 80f))),
                            NodeId("TopSpeedReq") to
                                NodeLayout(bounds = Rect(origin = Point(x = 300f, y = 40f), size = Size(width = 180f, height = 100f))),
                            NodeId("SafetyReq") to
                                NodeLayout(bounds = Rect(origin = Point(x = 600f, y = 40f), size = Size(width = 180f, height = 100f))),
                            NodeId("BrakingReq") to
                                NodeLayout(bounds = Rect(origin = Point(x = 600f, y = 200f), size = Size(width = 180f, height = 100f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("satisfy:Vehicle::TopSpeedReq") to
                                EdgeRoute.Direct(source = Point(x = 140f, y = 80f), target = Point(x = 300f, y = 80f)),
                            EdgeId("derive:BrakingReq::SafetyReq") to
                                EdgeRoute.Direct(source = Point(x = 690f, y = 200f), target = Point(x = 690f, y = 140f)),
                        ),
                    groups = emptyMap(),
                )
            val tex = KumlLatexRenderer.toLatex(model = model, diagram = diagram, layoutResult = layout)
            tex shouldContain "\\guillemotleft{}satisfy\\guillemotright{}"
            tex shouldContain "\\guillemotleft{}deriveReqt\\guillemotright{}"
        }

        // ─── STM ──────────────────────────────────────────────────────────

        "STM TikZ contains transition label trigger" {
            val model =
                Sysml2Model(
                    name = "Engine",
                    definitions =
                        listOf(
                            StateDefinition(id = "Off", name = "Off"),
                            StateDefinition(id = "On", name = "On"),
                        ),
                    usages =
                        listOf(
                            TransitionUsage(
                                id = "transition:Off::On",
                                name = "powerOn",
                                sourceStateId = "Off",
                                targetStateId = "On",
                                trigger = "powerOn",
                                guard = "ready",
                            ),
                        ),
                )
            val diagram = StmDiagram(name = "Engine", elementIds = listOf("Off", "On"))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 500f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("Off") to
                                NodeLayout(bounds = Rect(origin = Point(x = 40f, y = 60f), size = Size(width = 120f, height = 60f))),
                            NodeId("On") to
                                NodeLayout(bounds = Rect(origin = Point(x = 300f, y = 60f), size = Size(width = 120f, height = 60f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("transition:Off::On") to
                                EdgeRoute.Direct(source = Point(x = 160f, y = 90f), target = Point(x = 300f, y = 90f)),
                        ),
                    groups = emptyMap(),
                )
            val tex = KumlLatexRenderer.toLatex(model = model, diagram = diagram, layoutResult = layout)
            tex shouldContain "powerOn [ready]"
            tex shouldContain "kuml-sysml2-edge-solid"
        }

        // ─── ACT ──────────────────────────────────────────────────────────

        "ACT TikZ contains [guard] and «Order» labels" {
            val model =
                Sysml2Model(
                    name = "Order",
                    definitions =
                        listOf(
                            ActionDefinition(id = "Start", name = "Start", kind = ActivityNodeKind.Initial),
                            ActionDefinition(id = "Validate", name = "Validate"),
                            ActionDefinition(id = "Ship", name = "Ship"),
                        ),
                    usages =
                        listOf(
                            ControlFlowUsage(
                                id = "controlFlow:Start::Validate",
                                name = "f1",
                                sourceNodeId = "Start",
                                targetNodeId = "Validate",
                                guard = "ready",
                            ),
                            dev.kuml.sysml2.ObjectFlowUsage(
                                id = "objectFlow:Validate::Ship",
                                name = "f2",
                                sourceNodeId = "Validate",
                                targetNodeId = "Ship",
                                objectType = "Order",
                            ),
                        ),
                )
            val diagram = ActDiagram(name = "Order", elementIds = listOf("Start", "Validate", "Ship"))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 600f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("Start") to
                                NodeLayout(bounds = Rect(origin = Point(x = 40f, y = 80f), size = Size(width = 30f, height = 30f))),
                            NodeId("Validate") to
                                NodeLayout(bounds = Rect(origin = Point(x = 160f, y = 60f), size = Size(width = 140f, height = 60f))),
                            NodeId("Ship") to
                                NodeLayout(bounds = Rect(origin = Point(x = 380f, y = 60f), size = Size(width = 140f, height = 60f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("controlFlow:Start::Validate") to
                                EdgeRoute.Direct(source = Point(x = 70f, y = 95f), target = Point(x = 160f, y = 90f)),
                            EdgeId("objectFlow:Validate::Ship") to
                                EdgeRoute.Direct(source = Point(x = 300f, y = 90f), target = Point(x = 380f, y = 90f)),
                        ),
                    groups = emptyMap(),
                )
            val tex = KumlLatexRenderer.toLatex(model = model, diagram = diagram, layoutResult = layout)
            tex shouldContain "[ready]"
            tex shouldContain "\\guillemotleft{}Order\\guillemotright{}" // wave 2F: the type of an object flow is written «Type»
        }

        // ─── PAR ──────────────────────────────────────────────────────────

        "PAR TikZ uses the binding edge style on bindings" {
            val model =
                Sysml2Model(
                    name = "F=ma",
                    definitions =
                        listOf(
                            PartDefinition(id = "Vehicle", name = "Vehicle"),
                            ConstraintDefinition(id = "NewtonsLaw", name = "NewtonsLaw"),
                        ),
                    usages =
                        listOf(
                            BindingConnectorUsage(
                                id = "binding:NewtonsLaw::m::Vehicle::mass",
                                name = "b1",
                                sourceEndId = "NewtonsLaw::m",
                                targetEndId = "Vehicle::mass",
                            ),
                        ),
                )
            val diagram = ParDiagram(name = "F=ma", elementIds = listOf("Vehicle", "NewtonsLaw"))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 500f, height = 220f),
                    nodes =
                        mapOf(
                            NodeId("Vehicle") to
                                NodeLayout(bounds = Rect(origin = Point(x = 40f, y = 60f), size = Size(width = 140f, height = 80f))),
                            NodeId("NewtonsLaw") to
                                NodeLayout(bounds = Rect(origin = Point(x = 300f, y = 60f), size = Size(width = 140f, height = 80f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("binding:NewtonsLaw::m::Vehicle::mass") to
                                EdgeRoute.Direct(source = Point(x = 180f, y = 100f), target = Point(x = 300f, y = 100f)),
                        ),
                    groups = emptyMap(),
                )
            val tex = KumlLatexRenderer.toLatex(model = model, diagram = diagram, layoutResult = layout)
            tex shouldContain "kuml-sysml2-edge-binding"
        }

        // ─── Label backdrop (Nacharbeit 18) ───────────────────────────────

        fun sysmlEdge(metadata: Sysml2EdgeMetadata): String {
            val out = StringBuilder()
            Sysml2EdgeLatexRenderer.render(
                route = EdgeRoute.Direct(source = Point(x = 0f, y = 0f), target = Point(x = 0f, y = 100f)),
                metadata = metadata,
                options = LatexRenderOptions.DEFAULT,
                out = out,
            )
            return out.toString()
        }

        val backdrop = "fill=white, inner sep=1.5pt"

        "edge label and stereotype both get above=1pt and the white backdrop, stereotype is raised by 11pt" {
            val tex = sysmlEdge(Sysml2EdgeMetadata(stereotype = "include", label = "guard"))
            tex shouldContain "node[midway, above=1pt, yshift=11pt, $backdrop]{\\textit{include}}"
            tex shouldContain "node[midway, above=1pt, $backdrop]{guard}"
        }

        "edge with label only has the backdrop and no yshift" {
            val tex = sysmlEdge(Sysml2EdgeMetadata(label = "guard"))
            tex shouldContain "node[midway, above=1pt, $backdrop]{guard}"
            tex shouldNotContain "yshift"
        }

        "edge with stereotype only has the backdrop and no yshift" {
            val tex = sysmlEdge(Sysml2EdgeMetadata(stereotype = "satisfy"))
            tex shouldContain "node[midway, above=1pt, $backdrop]{\\textit{satisfy}}"
            tex shouldNotContain "yshift"
        }

        "edge without label or stereotype emits no node" {
            sysmlEdge(Sysml2EdgeMetadata()) shouldNotContain "node["
        }

        "edge label is LaTeX-escaped exactly once" {
            val tex = sysmlEdge(Sysml2EdgeMetadata(label = "a_b&c"))
            tex shouldContain "{a\\_b\\&c}"
            tex shouldNotContain "\\\\_"
            tex shouldNotContain "\\\\&"
        }

        "UML renderer draws the association name on a backdrop" {
            val a = UmlClass(id = "A", name = "A")
            val b = UmlClass(id = "B", name = "B")
            val assoc =
                UmlAssociation(
                    id = "assoc1",
                    name = "owns",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "A", multiplicity = Multiplicity()),
                            UmlAssociationEnd(typeId = "B", multiplicity = Multiplicity()),
                        ),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 500f, height = 300f),
                    nodes =
                        mapOf(
                            NodeId(
                                "A",
                            ) to NodeLayout(bounds = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 80f, height = 60f))),
                            NodeId("B") to
                                NodeLayout(bounds = Rect(origin = Point(x = 200f, y = 0f), size = Size(width = 80f, height = 60f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("assoc1") to EdgeRoute.Direct(source = Point(x = 80f, y = 30f), target = Point(x = 200f, y = 30f)),
                        ),
                    groups = emptyMap(),
                )
            val tex =
                KumlLatexRenderer.toLatex(
                    diagram = KumlDiagram(name = "T", elements = listOf(a, b, assoc)),
                    layoutResult = layout,
                )
            val drawn = tex.lines().filter { it.contains("\\draw[kuml-association]") }
            drawn.isNotEmpty() shouldBe true
            val nodes = tex.lines().filter { it.contains("{owns}") }
            nodes.size shouldBe 1
            nodes.single() shouldContain "fill=white"
        }
    })
