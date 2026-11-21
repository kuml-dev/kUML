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

        "unchanged diagram: loop without conflict renders identically twice" {
            build(ErmNotation.MARTIN, 200f, withRightNeighbour = false) shouldBe build(ErmNotation.MARTIN, 200f, withRightNeighbour = false)
        }
    })

private fun pk(name: String): ErmAttribute = ErmAttribute(id = name, name = name, type = ErmDataType.Uuid, primaryKey = true)
