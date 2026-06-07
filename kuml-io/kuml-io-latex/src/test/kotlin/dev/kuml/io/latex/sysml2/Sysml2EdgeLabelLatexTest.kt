package dev.kuml.io.latex.sysml2

import dev.kuml.io.latex.KumlLatexRenderer
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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain

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
                    canvas = Size(800f, 220f),
                    nodes =
                        mapOf(
                            NodeId("Reader") to NodeLayout(bounds = Rect(Point(20f, 60f), Size(60f, 100f))),
                            NodeId("BorrowBook") to NodeLayout(bounds = Rect(Point(160f, 30f), Size(160f, 70f))),
                            NodeId("Authenticate") to NodeLayout(bounds = Rect(Point(420f, 30f), Size(160f, 70f))),
                            NodeId("PayLateFee") to NodeLayout(bounds = Rect(Point(160f, 130f), Size(160f, 70f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("assoc:Reader::BorrowBook") to
                                EdgeRoute.Direct(source = Point(80f, 110f), target = Point(160f, 65f)),
                            EdgeId("include:BorrowBook::Authenticate") to
                                EdgeRoute.Direct(source = Point(320f, 65f), target = Point(420f, 65f)),
                            EdgeId("extend:PayLateFee::BorrowBook") to
                                EdgeRoute.Direct(source = Point(240f, 130f), target = Point(240f, 100f)),
                        ),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(model, diagram, layout)
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
                    canvas = Size(900f, 400f),
                    nodes =
                        mapOf(
                            NodeId("Vehicle") to NodeLayout(bounds = Rect(Point(20f, 40f), Size(120f, 80f))),
                            NodeId("TopSpeedReq") to NodeLayout(bounds = Rect(Point(300f, 40f), Size(180f, 100f))),
                            NodeId("SafetyReq") to NodeLayout(bounds = Rect(Point(600f, 40f), Size(180f, 100f))),
                            NodeId("BrakingReq") to NodeLayout(bounds = Rect(Point(600f, 200f), Size(180f, 100f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("satisfy:Vehicle::TopSpeedReq") to
                                EdgeRoute.Direct(source = Point(140f, 80f), target = Point(300f, 80f)),
                            EdgeId("derive:BrakingReq::SafetyReq") to
                                EdgeRoute.Direct(source = Point(690f, 200f), target = Point(690f, 140f)),
                        ),
                    groups = emptyMap(),
                )
            val tex = KumlLatexRenderer.toLatex(model, diagram, layout)
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
                    canvas = Size(500f, 200f),
                    nodes =
                        mapOf(
                            NodeId("Off") to NodeLayout(bounds = Rect(Point(40f, 60f), Size(120f, 60f))),
                            NodeId("On") to NodeLayout(bounds = Rect(Point(300f, 60f), Size(120f, 60f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("transition:Off::On") to
                                EdgeRoute.Direct(source = Point(160f, 90f), target = Point(300f, 90f)),
                        ),
                    groups = emptyMap(),
                )
            val tex = KumlLatexRenderer.toLatex(model, diagram, layout)
            tex shouldContain "powerOn [ready]"
            tex shouldContain "kuml-sysml2-edge-solid"
        }

        // ─── ACT ──────────────────────────────────────────────────────────

        "ACT TikZ contains [guard] and [Order] labels" {
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
                    canvas = Size(600f, 200f),
                    nodes =
                        mapOf(
                            NodeId("Start") to NodeLayout(bounds = Rect(Point(40f, 80f), Size(30f, 30f))),
                            NodeId("Validate") to NodeLayout(bounds = Rect(Point(160f, 60f), Size(140f, 60f))),
                            NodeId("Ship") to NodeLayout(bounds = Rect(Point(380f, 60f), Size(140f, 60f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("controlFlow:Start::Validate") to
                                EdgeRoute.Direct(source = Point(70f, 95f), target = Point(160f, 90f)),
                            EdgeId("objectFlow:Validate::Ship") to
                                EdgeRoute.Direct(source = Point(300f, 90f), target = Point(380f, 90f)),
                        ),
                    groups = emptyMap(),
                )
            val tex = KumlLatexRenderer.toLatex(model, diagram, layout)
            tex shouldContain "[ready]"
            tex shouldContain "[Order]"
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
                    canvas = Size(500f, 220f),
                    nodes =
                        mapOf(
                            NodeId("Vehicle") to NodeLayout(bounds = Rect(Point(40f, 60f), Size(140f, 80f))),
                            NodeId("NewtonsLaw") to NodeLayout(bounds = Rect(Point(300f, 60f), Size(140f, 80f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("binding:NewtonsLaw::m::Vehicle::mass") to
                                EdgeRoute.Direct(source = Point(180f, 100f), target = Point(300f, 100f)),
                        ),
                    groups = emptyMap(),
                )
            val tex = KumlLatexRenderer.toLatex(model, diagram, layout)
            tex shouldContain "kuml-sysml2-edge-binding"
        }
    })
