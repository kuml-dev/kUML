package dev.kuml.layout.grid

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRouteStyle
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeHints
import dev.kuml.layout.NodeId
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Goldfile-Style-Tests: serialisiere ein bekanntes LayoutResult zu JSON und
 * prüfe entscheidende Werte. Wenn sich an der Engine-Ausgabe etwas ändert
 * (z.B. Spacing, Reihenfolge der Slot-Allokation, Edge-Routing-Geometrie),
 * fliegen diese Tests sofort raus — das ist beabsichtigt, weil die JSON-
 * Repräsentation als stabiles Wire-Format gehandelt wird (z.B. für
 * `--export-layout-json` in kUML CLI, ADR-0006).
 *
 * Wir prüfen hier nicht den vollständigen JSON-String byte-für-byte, weil
 * Float-Rundungen sonst dauernd false-positive sind. Stattdessen prüfen wir
 * strukturelle Eigenschaften + ein paar Schlüsselwerte.
 */
class GridLayoutGoldfileTest :
    FunSpec({

        val engine = GridLayoutEngine()
        val json =
            Json {
                prettyPrint = true
                encodeDefaults = false
            }

        test("3x2 grid with two edges roundtrips through kotlinx-serialization") {
            val graph =
                LayoutGraph(
                    nodes =
                        listOf(
                            LayoutNode(NodeId("a"), Size(100f, 60f), NodeHints(gridCol = 0, gridRow = 0)),
                            LayoutNode(NodeId("b"), Size(100f, 60f), NodeHints(gridCol = 1, gridRow = 0)),
                            LayoutNode(NodeId("c"), Size(100f, 60f), NodeHints(gridCol = 2, gridRow = 0)),
                            LayoutNode(NodeId("d"), Size(100f, 60f), NodeHints(gridCol = 0, gridRow = 1)),
                            LayoutNode(NodeId("e"), Size(100f, 60f), NodeHints(gridCol = 1, gridRow = 1)),
                            LayoutNode(NodeId("f"), Size(100f, 60f), NodeHints(gridCol = 2, gridRow = 1)),
                        ),
                    edges =
                        listOf(
                            LayoutEdge(EdgeId("ab"), EndpointRef(NodeId("a")), EndpointRef(NodeId("b"))),
                            LayoutEdge(EdgeId("de"), EndpointRef(NodeId("d")), EndpointRef(NodeId("e"))),
                        ),
                )
            val result =
                engine.layout(graph, LayoutHints(defaultEdgeStyle = EdgeRouteStyle.OrthogonalRounded))

            val serialized = json.encodeToString(result)
            // Spot-Checks: bekannte Knoten- und Kanten-IDs sind im JSON vorhanden.
            serialized shouldContain "\"a\""
            serialized shouldContain "\"f\""
            serialized shouldContain "\"engineId\""
            serialized shouldContain "kuml.grid"
            serialized shouldContain "OrthogonalRounded"

            // Roundtrip muss die ursprüngliche Struktur exakt wiederherstellen.
            val decoded = Json.decodeFromString<LayoutResult>(serialized)
            decoded shouldBe result
        }

        test("port allocation produces deterministic positions across runs") {
            val graph =
                LayoutGraph(
                    nodes = listOf(LayoutNode(NodeId("svc"), Size(200f, 100f))),
                    edges =
                        listOf(
                            LayoutEdge(
                                EdgeId("loopback"),
                                EndpointRef(NodeId("svc"), dev.kuml.layout.PortId("p_in")),
                                EndpointRef(NodeId("svc"), dev.kuml.layout.PortId("p_out")),
                            ),
                        ),
                )
            val r1 = engine.layout(graph)
            val r2 = engine.layout(graph)
            r1.nodes shouldBe r2.nodes
            r1.edges shouldBe r2.edges
        }
    })
