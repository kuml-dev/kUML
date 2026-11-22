@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.svg.erm

import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNotation
import dev.kuml.erm.model.ErmRelationship
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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Nacharbeit 129: a self-reference is drawn on the left of its entity when another edge occupies the right-hand band and
 * the left one is free. Structural checks on coordinates only (no pixel or font dependence), for all three notations.
 */
class ErmSelfLoopSideSvgTest :
    StringSpec({
        val padding = SvgRenderOptions.DEFAULT.paddingPx

        fun build(
            notation: ErmNotation,
            nodeX: Float,
            withRightNeighbour: Boolean,
        ): String {
            val category = ErmEntity(id = "category", name = "Category", attributes = listOf(pk("id")))
            val other = ErmEntity(id = "other", name = "Other", attributes = listOf(pk("id")))
            val loop =
                ErmRelationship(
                    id = "loop",
                    name = "subcategory of",
                    sourceEntityId = "category",
                    targetEntityId = "category",
                    sourceCardinality = Cardinality.ZERO_ONE,
                    targetCardinality = Cardinality.ZERO_MANY,
                    sourceRole = "parent",
                    targetRole = "child",
                )
            val feeds =
                ErmRelationship(
                    id = "feeds",
                    name = null,
                    sourceEntityId = "category",
                    targetEntityId = "other",
                    sourceCardinality = Cardinality.ONE,
                    targetCardinality = Cardinality.ZERO_MANY,
                )
            val entities = if (withRightNeighbour) listOf(category, other) else listOf(category)
            val rels = if (withRightNeighbour) listOf(loop, feeds) else listOf(loop)
            val model = ErmModel(name = "Catalog", entities = entities, relationships = rels)
            val nodes = mutableMapOf(NodeId("category") to NodeLayout(Rect(Point(nodeX, 100f), Size(180f, 120f))))
            val edges =
                mutableMapOf<EdgeId, EdgeRoute>(
                    EdgeId("loop") to
                        EdgeRoute.OrthogonalRounded(
                            Point(nodeX, 140f),
                            Point(nodeX, 190f),
                            listOf(Point(nodeX - 10, 140f), Point(nodeX - 10, 190f)),
                            6f,
                        ),
                )
            if (withRightNeighbour) {
                nodes[NodeId("other")] = NodeLayout(Rect(Point(nodeX + 320f, 100f), Size(120f, 120f)))
                edges[EdgeId("feeds")] = EdgeRoute.Direct(Point(nodeX + 180f, 160f), Point(nodeX + 320f, 160f))
            }
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(nodeX + 360f + (if (withRightNeighbour) 120f else 0f), 260f),
                    nodes = nodes,
                    edges = edges,
                    groups = emptyMap(),
                )
            return KumlSvgRenderer.toSvg(
                model = model,
                diagram = ErmDiagram(name = "d", notation = notation),
                layoutResult = layout,
                theme = PlainTheme(),
                options = SvgRenderOptions(prettyPrint = false),
            )
        }

        for (notation in listOf(ErmNotation.MARTIN, ErmNotation.BACHMAN, ErmNotation.IDEF1X)) {
            "$notation: busy right side puts name and roles left of the entity" {
                val nodeX = 200f
                val svg = build(notation, nodeX, withRightNeighbour = true)
                val labels = edgeLabels(svg)
                for (text in listOf("subcategory of", "parent", "child")) {
                    val l = labels.single { it.text == text }
                    val half = (text.length * 6.6f + 8f) / 2f
                    // planned rectangle ends left of the entity's left border (x = nodeX + padding)
                    (l.x + half <= nodeX + padding + 0.5f) shouldBe true
                }
            }

            "$notation: free right side keeps the loop labels on the right" {
                val svg = build(notation, 200f, withRightNeighbour = false)
                val labels = edgeLabels(svg)
                for (text in listOf("subcategory of", "parent", "child")) {
                    val l = labels.single { it.text == text }
                    val half = (text.length * 6.6f + 8f) / 2f
                    (l.x - half >= 200f + 180f + padding - 0.5f) shouldBe true
                }
            }
        }

        "left loop close to the canvas edge: nothing lies at a negative x, canvas grows (shift)" {
            val svg = build(ErmNotation.MARTIN, 40f, withRightNeighbour = true)
            val labels = edgeLabels(svg)
            for (l in labels) {
                val half = (l.text.length * 6.6f + 8f) / 2f
                (l.x - half >= -0.5f) shouldBe true
            }
            val negative = Regex("""(?<![0-9.])x="(-[0-9.]+)"""").findAll(svg).toList()
            negative.size shouldBe 0
        }

        // Nacharbeit 132: right and left bands busy -> the loop goes above (TOP); top busy as well -> below (BOTTOM).
        fun buildVertical(
            notation: ErmNotation,
            nodeY: Float,
            blockTop: Boolean,
            canvasHeight: Float,
        ): String {
            fun ent(id: String) = ErmEntity(id = id, name = id.replaceFirstChar { it.uppercase() }, attributes = listOf(pk("id")))

            fun rel(
                id: String,
                a: String,
                b: String,
            ) = ErmRelationship(
                id = id,
                name = null,
                sourceEntityId = a,
                targetEntityId = b,
                sourceCardinality = Cardinality.ONE,
                targetCardinality = Cardinality.ZERO_MANY,
            )
            val loop =
                ErmRelationship(
                    id = "loop",
                    name = "subcategory of",
                    sourceEntityId = "category",
                    targetEntityId = "category",
                    sourceCardinality = Cardinality.ZERO_ONE,
                    targetCardinality = Cardinality.ZERO_MANY,
                    sourceRole = "parent",
                    targetRole = "child",
                )
            val entities = mutableListOf(ent("category"), ent("east"), ent("west"))
            val rels = mutableListOf(loop, rel("re", "category", "east"), rel("rw", "west", "category"))
            val nodes =
                mutableMapOf(
                    NodeId("category") to NodeLayout(Rect(Point(300f, nodeY), Size(180f, 120f))),
                    NodeId("east") to NodeLayout(Rect(Point(640f, nodeY), Size(120f, 120f))),
                    NodeId("west") to NodeLayout(Rect(Point(40f, nodeY), Size(120f, 120f))),
                )
            val edges =
                mutableMapOf<EdgeId, EdgeRoute>(
                    EdgeId("loop") to
                        EdgeRoute.OrthogonalRounded(
                            Point(480f, nodeY + 40),
                            Point(480f, nodeY + 90),
                            listOf(Point(470f, nodeY + 40), Point(470f, nodeY + 90)),
                            6f,
                        ),
                    EdgeId("re") to EdgeRoute.Direct(Point(480f, nodeY + 60), Point(640f, nodeY + 60)),
                    EdgeId("rw") to EdgeRoute.Direct(Point(160f, nodeY + 60), Point(300f, nodeY + 60)),
                )
            if (blockTop) {
                entities += ent("north")
                rels += rel("rn", "north", "category")
                nodes[NodeId("north")] = NodeLayout(Rect(Point(350f, nodeY - 100f), Size(80f, 40f)))
                edges[EdgeId("rn")] = EdgeRoute.Direct(Point(390f, nodeY - 60f), Point(390f, nodeY))
            }
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(800f, canvasHeight),
                    nodes = nodes,
                    edges = edges,
                    groups = emptyMap(),
                )
            return KumlSvgRenderer.toSvg(
                model = ErmModel(name = "Catalog", entities = entities, relationships = rels),
                diagram = ErmDiagram(name = "d", notation = notation),
                layoutResult = layout,
                theme = PlainTheme(),
                options = SvgRenderOptions(prettyPrint = false),
            )
        }

        fun viewBoxSize(svg: String): List<Float> =
            Regex("""viewBox="(-?[0-9.]+) (-?[0-9.]+) ([0-9.]+) ([0-9.]+)"""")
                .find(svg)!!
                .groupValues
                .drop(3)
                .map { it.toFloat() }

        for (notation in listOf(ErmNotation.MARTIN, ErmNotation.BACHMAN, ErmNotation.IDEF1X)) {
            "$notation: right and left busy puts the loop above; labels stay out from between the legs and inside the canvas" {
                val svg = buildVertical(notation, nodeY = 40f, blockTop = false, canvasHeight = 260f)
                val (_, h) = viewBoxSize(svg)
                val labels = edgeLabels(svg)
                val legL = 300f + 180f * 0.30f + padding
                val legR = 300f + 180f * 0.70f + padding
                for (text in listOf("subcategory of", "parent", "child")) {
                    val l = labels.single { it.text == text }
                    val half = (text.length * 6.6f + 8f) / 2f
                    // the role labels sit beside the legs, never between them
                    if (text != "subcategory of") ((l.x + half <= legL + 0.5f) || (l.x - half >= legR - 0.5f)) shouldBe true
                    ((l.y >= 0f) && (l.y <= h)) shouldBe true
                }
            }

            "$notation: right, left and top busy puts the loop below and the canvas grows" {
                val svg = buildVertical(notation, nodeY = 120f, blockTop = true, canvasHeight = 250f)
                val (_, h) = viewBoxSize(svg)
                // outer line at 120 + 120 + 32 = 272 (+ padding), beyond the 250 px layout canvas
                (h >= 272f + padding) shouldBe true
                for (l in edgeLabels(svg)) ((l.y >= 0f) && (l.y <= h)) shouldBe true
            }
        }

        "unchanged diagram: loop without conflict renders identically twice" {
            build(ErmNotation.MARTIN, 200f, withRightNeighbour = false) shouldBe build(ErmNotation.MARTIN, 200f, withRightNeighbour = false)
        }
    })

private fun pk(name: String): ErmAttribute = ErmAttribute(id = name, name = name, type = ErmDataType.Uuid, primaryKey = true)
