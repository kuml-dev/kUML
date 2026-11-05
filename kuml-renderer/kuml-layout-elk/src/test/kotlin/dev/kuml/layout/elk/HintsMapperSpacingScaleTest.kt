package dev.kuml.layout.elk

import dev.kuml.layout.GroupId
import dev.kuml.layout.LAYOUT_SPACING_SCALE
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutHints
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.core.math.ElkPadding
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.graph.util.ElkGraphUtil

/** Spacing is scaled exactly once, by [LAYOUT_SPACING_SCALE], in [HintsMapper]. Tests never pin the factor's value. */
class HintsMapperSpacingScaleTest :
    FunSpec({
        val s = LAYOUT_SPACING_SCALE.toDouble()

        fun root(
            hints: LayoutHints = LayoutHints(),
            config: ElkEngineConfiguration = ElkEngineConfiguration(),
        ) = ElkGraphUtil.createGraph().also { HintsMapper.applyGlobalHints(root = it, hints = hints, config = config) }

        test("the scale factor is positive") {
            s shouldBeGreaterThan 0.0
        }

        test("defaults are scaled exactly once on the root") {
            val r = root()
            r.getProperty(CoreOptions.SPACING_NODE_NODE) shouldBe (40f * LAYOUT_SPACING_SCALE).toDouble()
            r.getProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS) shouldBe (90f * LAYOUT_SPACING_SCALE).toDouble()
            r.getProperty(CoreOptions.SPACING_EDGE_NODE) shouldBe (30f * LAYOUT_SPACING_SCALE).toDouble()
            r.getProperty(CoreOptions.SPACING_EDGE_EDGE) shouldBe (12f * LAYOUT_SPACING_SCALE).toDouble()
        }

        test("layer-spacing hint override is scaled, not the engine default") {
            val hints = LayoutHints(spacing = LayoutHints().spacing.copy(layerToLayer = 130f, nodeToNode = 110f))
            val r = root(hints)
            r.getProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS) shouldBe (130f * LAYOUT_SPACING_SCALE).toDouble()
            r.getProperty(CoreOptions.SPACING_NODE_NODE) shouldBe (110f * LAYOUT_SPACING_SCALE).toDouble()
        }

        test("group padding and edge label spacing are not scaled") {
            val r = root()
            r.getProperty(CoreOptions.PADDING) shouldBe ElkPadding(16.0)
            r.getProperty(CoreOptions.SPACING_EDGE_LABEL) shouldBe 4.0
        }

        test("lower bounds apply after scaling: edge-edge at least 8, between-layer edge-node corridor at least 25") {
            val tight = LayoutHints(spacing = LayoutHints().spacing.copy(edgeToEdge = 1f))
            root(tight).getProperty(CoreOptions.SPACING_EDGE_EDGE) shouldBe 8.0
            val small = ElkEngineConfiguration(edgeNodeSpacing = 1f)
            root(config = small).getProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS) shouldBe 25.0
        }

        test("edgeToEdgeBetweenLayers (PAL channel size) is not scaled") {
            val hints = LayoutHints(spacing = LayoutHints().spacing.copy(edgeToEdgeBetweenLayers = 16f))
            root(hints).getProperty(LayeredOptions.SPACING_EDGE_EDGE_BETWEEN_LAYERS) shouldBe 16.0
        }

        test("compound nodes receive the same scaled spacing as the root") {
            val graph =
                LayoutGraph(
                    nodes = emptyList(),
                    edges = emptyList(),
                    groups = listOf(LayoutGroup(id = GroupId("g"), layoutAsCompound = true)),
                )
            val builder = ElkGraphBuilder(graph)
            builder.build()
            HintsMapper.applyGroupPadding(builder = builder, hints = LayoutHints(), config = ElkEngineConfiguration())
            val g = builder.groupMap.getValue(GroupId("g"))
            val r = root()
            g.getProperty(CoreOptions.SPACING_NODE_NODE) shouldBe r.getProperty(CoreOptions.SPACING_NODE_NODE)
            g.getProperty(CoreOptions.SPACING_EDGE_EDGE) shouldBe r.getProperty(CoreOptions.SPACING_EDGE_EDGE)
            g.getProperty(CoreOptions.SPACING_EDGE_NODE) shouldBe r.getProperty(CoreOptions.SPACING_EDGE_NODE)
            g.getProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS) shouldBe
                r.getProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS)
            g.getProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS) shouldBe
                r.getProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS)
        }
    })
