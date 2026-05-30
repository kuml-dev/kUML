package dev.kuml.layout

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Round-Trip-Serialisierungstests für die Layout-API.
 *
 * Jeder Test kodiert einen Wert nach JSON und dekodiert ihn zurück;
 * das rekonstruierte Objekt muss dem Original gleich sein.
 * Sicherung des Serialisierungsvertrags gemäß Spec (Designentwurf Phase 1).
 */
class LayoutApiSerializationTest : FunSpec({

    val json = Json { prettyPrint = false }

    test("Test 1 — LayoutGraph mit 2 Knoten und 1 Kante: Round-Trip") {
        val graph =
            LayoutGraph(
                nodes =
                    listOf(
                        LayoutNode(
                            id = NodeId("n1"),
                            intrinsicSize = Size(120f, 60f),
                            hints = NodeHints(gridCol = 0, gridRow = 0),
                        ),
                        LayoutNode(
                            id = NodeId("n2"),
                            intrinsicSize = Size(100f, 80f),
                            hints = NodeHints.NONE,
                            groupId = GroupId("g1"),
                        ),
                    ),
                edges =
                    listOf(
                        LayoutEdge(
                            id = EdgeId("e1"),
                            source = EndpointRef(nodeId = NodeId("n1")),
                            target = EndpointRef(nodeId = NodeId("n2"), portId = PortId("p1")),
                            hints = EdgeHints(routeStyle = EdgeRouteStyle.OrthogonalRounded),
                        ),
                    ),
                groups =
                    listOf(
                        LayoutGroup(id = GroupId("g1"), padding = Insets(8f, 8f, 8f, 8f)),
                    ),
            )

        val encoded = json.encodeToString(graph)
        val decoded = json.decodeFromString<LayoutGraph>(encoded)
        decoded shouldBe graph
    }

    test("Test 2 — LayoutResult mit allen 4 EdgeRoute-Subtypen: Round-Trip") {
        val result =
            LayoutResult(
                engineId = LayoutEngineId("kuml.test"),
                seed = 42L,
                canvas = Size(800f, 600f),
                nodes =
                    mapOf(
                        NodeId("n1") to NodeLayout(bounds = Rect(Point(10f, 10f), Size(120f, 60f))),
                        NodeId("n2") to
                            NodeLayout(
                                bounds = Rect(Point(200f, 10f), Size(100f, 80f)),
                                ports = mapOf(PortId("p1") to Point(200f, 50f)),
                            ),
                    ),
                edges =
                    mapOf(
                        EdgeId("e-direct") to
                            EdgeRoute.Direct(
                                source = Point(130f, 40f),
                                target = Point(200f, 50f),
                            ),
                        EdgeId("e-ortho") to
                            EdgeRoute.OrthogonalRounded(
                                source = Point(130f, 40f),
                                target = Point(200f, 50f),
                                waypoints = listOf(Point(165f, 40f), Point(165f, 50f)),
                                cornerRadiusPx = 4f,
                            ),
                        EdgeId("e-tree") to
                            EdgeRoute.TreeRounded(
                                source = Point(130f, 40f),
                                target = Point(200f, 50f),
                                waypoints = listOf(Point(165f, 40f)),
                                cornerRadiusPx = 6f,
                            ),
                        EdgeId("e-bezier") to
                            EdgeRoute.Bezier(
                                source = Point(130f, 40f),
                                target = Point(200f, 50f),
                                controlPoints = listOf(Point(150f, 20f), Point(180f, 70f)),
                            ),
                    ),
                groups = emptyMap(),
                warnings =
                    listOf(
                        LayoutWarning(
                            code = "hint.ignored.gridCol",
                            message = "Engine unterstützt keine Grid-Hints",
                            affectedNodes = listOf(NodeId("n1")),
                        ),
                    ),
            )

        val encoded = json.encodeToString(result)
        val decoded = json.decodeFromString<LayoutResult>(encoded)
        decoded shouldBe result
    }

    test("Test 3 — NodeHints mit allen 6 RelativeConstraint-Subtypen: Round-Trip") {
        val hints =
            NodeHints(
                gridCol = 2,
                gridRow = 1,
                gridColSpan = 2,
                gridRowSpan = 1,
                pinned = true,
                relative =
                    listOf(
                        RelativeConstraint.Above(NodeId("n-above")),
                        RelativeConstraint.Below(NodeId("n-below")),
                        RelativeConstraint.LeftOf(NodeId("n-left")),
                        RelativeConstraint.RightOf(NodeId("n-right")),
                        RelativeConstraint.SameRowAs(NodeId("n-row")),
                        RelativeConstraint.SameColAs(NodeId("n-col")),
                    ),
            )

        val encoded = json.encodeToString(hints)
        val decoded = json.decodeFromString<NodeHints>(encoded)
        decoded shouldBe hints
    }

    test("Test 4 — LayoutHints mit befülltem engineOptions: Round-Trip") {
        val hints =
            LayoutHints(
                deterministicSeed = 12345L,
                timeBudgetMillis = 2_000,
                defaultEdgeStyle = EdgeRouteStyle.Bezier,
                direction = LayoutDirection.LeftToRight,
                spacing = Spacing(nodeToNode = 50f, edgeToEdge = 10f, groupPadding = 20f),
                engineOptions =
                    mapOf(
                        "elk.algorithm" to "layered",
                        "elk.direction" to "RIGHT",
                        "elk.layered.spacing.nodeNodeBetweenLayers" to "50",
                    ),
            )

        val encoded = json.encodeToString(hints)
        val decoded = json.decodeFromString<LayoutHints>(encoded)
        decoded shouldBe hints
    }

    test("Test 5 — LayoutCapabilities mit allen DiagramKind-Werten: Round-Trip") {
        val capabilities =
            LayoutCapabilities(
                deterministic = true,
                supportedDiagramKinds = DiagramKind.entries.toSet(),
                supportedEdgeStyles =
                    setOf(
                        EdgeRouteStyle.Direct,
                        EdgeRouteStyle.OrthogonalRounded,
                        EdgeRouteStyle.TreeRounded,
                        EdgeRouteStyle.Bezier,
                    ),
                respectsGridHints = true,
                respectsRelativeConstraints = true,
                maxRecommendedNodes = 500,
            )

        val encoded = json.encodeToString(capabilities)
        val decoded = json.decodeFromString<LayoutCapabilities>(encoded)
        decoded shouldBe capabilities
    }
})
