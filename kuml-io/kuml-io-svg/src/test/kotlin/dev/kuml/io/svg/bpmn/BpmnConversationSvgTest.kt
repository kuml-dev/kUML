package dev.kuml.io.svg.bpmn

import dev.kuml.bpmn.model.BpmnConversation
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.CallConversation
import dev.kuml.bpmn.model.ConversationDiagram
import dev.kuml.bpmn.model.ConversationLink
import dev.kuml.bpmn.model.ConversationNode
import dev.kuml.bpmn.model.SubConversation
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * SVG-Renderer-Tests für BPMN-Conversation-Elemente.
 *
 * Prüft, dass [ConversationNode], [CallConversation], [SubConversation],
 * Conversation-Participants (Rechtecke) und [ConversationLink]s korrekte
 * SVG-Fragmente erzeugen.
 *
 * Kern-Prüfpunkte (gemäß BPMN 2.0 §9):
 * - Hexagon-Form (`<polygon>`) mit 6 Punkten (elongiert, Spitzen links/rechts)
 * - [CallConversation]: dicker Rand (`stroke-width="3"`)
 * - [SubConversation]: +-Marker (`<line>` Elemente)
 * - Participants: Rechteck (`<rect>`)
 * - Conversation Links: **kein** `marker-end` (BPMN 2.0 §9.5.3 — ungerichtet)
 *
 * V3.2.3 — BPMN Conversation Diagram: SVG-Renderer
 */
class BpmnConversationSvgTest :
    FunSpec({

        // ── Helpers ───────────────────────────────────────────────────────────────

        /** Minimales Layout mit einem einzelnen Knoten. */
        fun singleNodeLayout(
            id: String,
            w: Float = 50f,
            h: Float = 44f,
        ): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(w + 40f, h + 40f),
                nodes = mapOf(NodeId(id) to NodeLayout(bounds = Rect(Point(20f, 20f), Size(w, h)))),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        /** Layout für einen Participant-Knoten (100×60 px). */
        fun participantLayout(name: String): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(140f, 100f),
                nodes = mapOf(NodeId(name) to NodeLayout(bounds = Rect(Point(20f, 20f), Size(100f, 60f)))),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        /** Layout für zwei Knoten + eine Kante (Participant → ConversationNode). */
        fun twoNodeOneEdgeLayout(
            participantName: String,
            nodeId: String,
            linkId: String,
        ): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(280f, 100f),
                nodes =
                    mapOf(
                        NodeId(participantName) to NodeLayout(bounds = Rect(Point(20f, 20f), Size(100f, 60f))),
                        NodeId(nodeId) to NodeLayout(bounds = Rect(Point(180f, 28f), Size(50f, 44f))),
                    ),
                edges =
                    mapOf(
                        EdgeId(linkId) to
                            EdgeRoute.Direct(
                                source = Point(120f, 50f),
                                target = Point(180f, 50f),
                            ),
                    ),
                groups = emptyMap(),
            )

        /** Baut ein minimales BpmnModel + ConversationDiagram. */
        fun modelAndDiagram(conversation: BpmnConversation): Pair<BpmnModel, ConversationDiagram> {
            val model = BpmnModel(name = "TestModel", conversations = listOf(conversation))
            val diagram = ConversationDiagram(name = "TestDiagram", conversationId = conversation.id)
            return model to diagram
        }

        // ── ConversationNode (Hexagon) ────────────────────────────────────────────

        test("ConversationNode: SVG enthält <polygon> (Hexagon-Form)") {
            val node = ConversationNode(id = "n1", name = "Order", participants = listOf("A", "B"))
            val conv = BpmnConversation(id = "conv1", participants = listOf("A", "B"), nodes = listOf(node))
            val (model, diagram) = modelAndDiagram(conv)

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleNodeLayout("n1"), PlainTheme())

            svg shouldContain "<polygon"
            svg shouldContain "points="
        }

        test("ConversationNode: Hexagon hat 6 Punkte (6 Komma-Paare)") {
            val node = ConversationNode(id = "n1", participants = listOf("A", "B"))
            val conv = BpmnConversation(id = "conv1", participants = listOf("A", "B"), nodes = listOf(node))
            val (model, diagram) = modelAndDiagram(conv)

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleNodeLayout("n1"), PlainTheme())

            // Exactly 6 point-pairs in the polygon points attribute
            val polygonMatch = Regex("""points="([^"]+)"""").find(svg)
            val pointsAttr = polygonMatch?.groupValues?.get(1) ?: ""
            val pointCount =
                pointsAttr
                    .trim()
                    .split(" ")
                    .filter { it.contains(",") }
                    .size
            assert(pointCount == 6) { "Hexagon muss genau 6 Punkte haben, gefunden: $pointCount (points=$pointsAttr)" }
        }

        test("ConversationNode: Label im SVG sichtbar") {
            val node = ConversationNode(id = "n1", name = "Bestellung", participants = listOf("A", "B"))
            val conv = BpmnConversation(id = "conv1", participants = listOf("A", "B"), nodes = listOf(node))
            val (model, diagram) = modelAndDiagram(conv)

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleNodeLayout("n1"), PlainTheme())

            svg shouldContain "Bestellung"
        }

        test("ConversationNode: kurzes Label (≤8 Zeichen) erscheint nur einmal im SVG") {
            // "Bestellg" hat genau 8 Zeichen → internes Label, kein Untertitel
            val node = ConversationNode(id = "n1", name = "Bestellg", participants = listOf("A", "B"))
            val conv = BpmnConversation(id = "conv1", participants = listOf("A", "B"), nodes = listOf(node))
            val (model, diagram) = modelAndDiagram(conv)

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleNodeLayout("n1"), PlainTheme())

            val occurrences = svg.split("Bestellg").size - 1
            assert(occurrences == 1) { "Kurzes Label darf nur einmal im SVG erscheinen, gefunden: $occurrences" }
        }

        test("ConversationNode: langes Label (>8 Zeichen) erscheint nur einmal im SVG (kein Doppel-Rendering)") {
            // "Mitgliedsantrag" hat 15 Zeichen → nur Untertitel, KEIN internes Label
            val node = ConversationNode(id = "n1", name = "Mitgliedsantrag", participants = listOf("A", "B"))
            val conv = BpmnConversation(id = "conv1", participants = listOf("A", "B"), nodes = listOf(node))
            val (model, diagram) = modelAndDiagram(conv)

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleNodeLayout("n1"), PlainTheme())

            val occurrences = svg.split("Mitgliedsantrag").size - 1
            assert(occurrences == 1) {
                "Langes Label darf nur einmal im SVG erscheinen (kein Doppel-Rendering), gefunden: $occurrences"
            }
        }

        test("ConversationNode: normaler Rand (stroke-width=1.5)") {
            val node = ConversationNode(id = "n1", participants = listOf("A", "B"))
            val conv = BpmnConversation(id = "conv1", participants = listOf("A", "B"), nodes = listOf(node))
            val (model, diagram) = modelAndDiagram(conv)

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleNodeLayout("n1"), PlainTheme())

            svg shouldContain "stroke-width=\"1.5\""
        }

        // ── CallConversation (Hexagon, dicker Rand) ───────────────────────────────

        test("CallConversation: SVG enthält <polygon> mit dickem Rand (stroke-width=3)") {
            val node =
                CallConversation(
                    id = "cc1",
                    name = "ExtCollab",
                    participants = listOf("A", "B"),
                    calledCollaborationRef = "extRef",
                )
            val conv = BpmnConversation(id = "conv1", participants = listOf("A", "B"), nodes = listOf(node))
            val (model, diagram) = modelAndDiagram(conv)

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleNodeLayout("cc1"), PlainTheme())

            svg shouldContain "<polygon"
            svg shouldContain "stroke-width=\"3\""
        }

        // ── SubConversation (Hexagon + +-Marker) ──────────────────────────────────

        test("SubConversation: SVG enthält <polygon> und +-Marker (mindestens 2 <line> Elemente)") {
            val node = SubConversation(id = "sc1", name = "SubConv", participants = listOf("A", "B"))
            val conv = BpmnConversation(id = "conv1", participants = listOf("A", "B"), nodes = listOf(node))
            val (model, diagram) = modelAndDiagram(conv)

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleNodeLayout("sc1"), PlainTheme())

            svg shouldContain "<polygon"
            // +-Marker: horizontale und vertikale Linie
            val lineCount = svg.split("<line").size - 1
            assert(lineCount >= 2) { "SubConversation muss mindestens 2 <line>-Elemente für den +-Marker haben, gefunden: $lineCount" }
        }

        // ── Conversation Participant (Rechteck) ───────────────────────────────────

        test("Conversation Participant: SVG enthält <rect> (Rechteck)") {
            val conv =
                BpmnConversation(
                    id = "conv1",
                    participants = listOf("Kunde"),
                    nodes = listOf(ConversationNode(id = "n1", participants = listOf("Kunde", "Anbieter"))),
                )
            val model = BpmnModel(name = "M", conversations = listOf(conv))
            val diagram = ConversationDiagram(name = "D", conversationId = "conv1")

            val layout = participantLayout("Kunde")
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "<rect"
            svg shouldContain "Kunde"
        }

        test("Conversation Participant: Name fettgedruckt (font-weight=bold)") {
            val conv =
                BpmnConversation(
                    id = "conv1",
                    participants = listOf("Partner"),
                    nodes = listOf(ConversationNode(id = "n1", participants = listOf("Partner", "Kunde"))),
                )
            val model = BpmnModel(name = "M", conversations = listOf(conv))
            val diagram = ConversationDiagram(name = "D", conversationId = "conv1")

            val layout = participantLayout("Partner")
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "font-weight=\"bold\""
        }

        // ── Conversation Link (KEIN Pfeilkopf) ───────────────────────────────────

        test("ConversationLink: SVG enthält <path> ohne marker-end (BPMN 2.0 §9.5.3)") {
            val convNode = ConversationNode(id = "n1", participants = listOf("Kunde", "Anbieter"))
            val link =
                ConversationLink(
                    id = "link1",
                    participantRef = "Kunde",
                    conversationNodeRef = "n1",
                )
            val conv =
                BpmnConversation(
                    id = "conv1",
                    participants = listOf("Kunde", "Anbieter"),
                    nodes = listOf(convNode),
                    links = listOf(link),
                )
            val model = BpmnModel(name = "M", conversations = listOf(conv))
            val diagram = ConversationDiagram(name = "D", conversationId = "conv1")

            val layout = twoNodeOneEdgeLayout("Kunde", "n1", "link1")
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            // Link muss als Pfad vorhanden sein
            svg shouldContain "<path"
            // KEIN Pfeilkopf: marker-end darf nicht im Link-Pfad-Element stehen
            // (Hinweis: marker-Definitionen im defs-Block sind ok — wir suchen nur im Pfad)
            // Einfachste Prüfung: der Link-Pfad darf kein marker-end enthalten
            // Da kein marker definiert ist, gilt: kein "marker-end" in der SVG-Ausgabe
            svg shouldNotContain "marker-end"
        }

        test("ConversationLink: Optionales Name-Label erscheint im SVG") {
            val convNode = ConversationNode(id = "n1", participants = listOf("A", "B"))
            val link =
                ConversationLink(
                    id = "link1",
                    name = "Vertrag",
                    participantRef = "A",
                    conversationNodeRef = "n1",
                )
            val conv =
                BpmnConversation(
                    id = "conv1",
                    participants = listOf("A", "B"),
                    nodes = listOf(convNode),
                    links = listOf(link),
                )
            val model = BpmnModel(name = "M", conversations = listOf(conv))
            val diagram = ConversationDiagram(name = "D", conversationId = "conv1")

            val layout = twoNodeOneEdgeLayout("A", "n1", "link1")
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "Vertrag"
        }

        // ── Vollständiges Conversation-Diagramm (Smoke-Test + PNG-Sample) ─────────

        test("Smoke: vollständiges Conversation-Diagramm — 3 Participants, 2 Knoten, 4 Links") {
            val p1 = "Mitglied"
            val p2 = "Vorstand"
            val p3 = "Netzwerk"
            val n1 = ConversationNode(id = "n1", name = "Mitgliedsantrag", participants = listOf(p1, p2))
            val n2 = ConversationNode(id = "n2", name = "Wahlkampagne", participants = listOf(p2, p3))
            val links =
                listOf(
                    ConversationLink(id = "l1", participantRef = p1, conversationNodeRef = "n1"),
                    ConversationLink(id = "l2", participantRef = p2, conversationNodeRef = "n1"),
                    ConversationLink(id = "l3", participantRef = p2, conversationNodeRef = "n2"),
                    ConversationLink(id = "l4", participantRef = p3, conversationNodeRef = "n2"),
                )
            val conv =
                BpmnConversation(
                    id = "conv1",
                    name = "PdV-Kommunikation",
                    participants = listOf(p1, p2, p3),
                    nodes = listOf(n1, n2),
                    links = links,
                )
            val model = BpmnModel(name = "PdV", conversations = listOf(conv))
            val diagram = ConversationDiagram(name = "PdV-Kommunikation", conversationId = "conv1")

            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(500f, 200f),
                    nodes =
                        mapOf(
                            NodeId(p1) to NodeLayout(bounds = Rect(Point(20f, 70f), Size(100f, 60f))),
                            NodeId(p2) to NodeLayout(bounds = Rect(Point(200f, 70f), Size(100f, 60f))),
                            NodeId(p3) to NodeLayout(bounds = Rect(Point(380f, 70f), Size(100f, 60f))),
                            NodeId("n1") to NodeLayout(bounds = Rect(Point(100f, 78f), Size(50f, 44f))),
                            NodeId("n2") to NodeLayout(bounds = Rect(Point(300f, 78f), Size(50f, 44f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("l1") to EdgeRoute.Direct(Point(70f, 100f), Point(100f, 100f)),
                            EdgeId("l2") to EdgeRoute.Direct(Point(200f, 100f), Point(150f, 100f)),
                            EdgeId("l3") to EdgeRoute.Direct(Point(300f, 100f), Point(350f, 100f)),
                            EdgeId("l4") to EdgeRoute.Direct(Point(380f, 100f), Point(350f, 100f)),
                        ),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            // SVG-Grundstruktur
            svg shouldContain "<svg"
            // Participants als Rechtecke sichtbar
            svg shouldContain "Mitglied"
            svg shouldContain "Vorstand"
            svg shouldContain "Netzwerk"
            // Hexagon-Knoten vorhanden
            svg shouldContain "Mitgliedsantrag"
            svg shouldContain "Wahlkampagne"
            svg shouldContain "<polygon"
            // Kein Pfeilkopf auf Links
            svg shouldNotContain "marker-end"
            // Participant-Rechtecke vorhanden
            svg shouldContain "<rect"
            // Diagrammtitel
            svg shouldContain "PdV-Kommunikation"

            // PNG-Sample-Output für visuelle Inspektion
            SampleOutput.write("bpmn/conversation-pdv-kommunikation.svg", svg)
        }

        // ── Robustheit ────────────────────────────────────────────────────────────

        test("unbekannte conversationId: gibt minimales valides SVG zurück (kein Crash)") {
            val model = BpmnModel(name = "M")
            val diagram = ConversationDiagram(name = "D", conversationId = "does-not-exist")

            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(100f, 100f),
                    nodes = emptyMap(),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "<svg"
        }
    })
