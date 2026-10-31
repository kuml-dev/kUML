package dev.kuml.layout.elk

import dev.kuml.layout.LayoutHints
import dev.kuml.layout.ModelOrder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.alg.layered.options.OrderingStrategy
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.graph.util.ElkGraphUtil

class HintsMapperSeedTest :
    FunSpec({
        test("seed 0 and null never map to ELK's random seed 0") {
            HintsMapper.elkSeedOf(0L) shouldBe 1
            HintsMapper.elkSeedOf(null) shouldBe 1
        }

        test("non-zero seeds map to a stable non-zero int") {
            HintsMapper.elkSeedOf(42L) shouldBe HintsMapper.elkSeedOf(42L)
            HintsMapper.elkSeedOf(42L) shouldNotBe 0
            HintsMapper.elkSeedOf(Long.MIN_VALUE) shouldNotBe 0
            HintsMapper.elkSeedOf(1L shl 32 or 1L) shouldNotBe 0
        }

        test("global hints set RANDOM_SEED and model order strategy") {
            val root = ElkGraphUtil.createGraph()
            HintsMapper.applyGlobalHints(root = root, hints = LayoutHints(), config = ElkEngineConfiguration())
            root.getProperty(CoreOptions.RANDOM_SEED) shouldBe 1
            root.getProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY) shouldBe OrderingStrategy.NODES_AND_EDGES
        }

        test("ModelOrder.None leaves the ELK default strategy") {
            val root = ElkGraphUtil.createGraph()
            HintsMapper.applyGlobalHints(root = root, hints = LayoutHints(modelOrder = ModelOrder.None), config = ElkEngineConfiguration())
            root.getProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY) shouldNotBe OrderingStrategy.NODES_AND_EDGES
        }

        test("edge-to-edge spacing is at least 8") {
            val root = ElkGraphUtil.createGraph()
            val tight = LayoutHints(spacing = LayoutHints().spacing.copy(edgeToEdge = 2f))
            HintsMapper.applyGlobalHints(root = root, hints = tight, config = ElkEngineConfiguration())
            root.getProperty(CoreOptions.SPACING_EDGE_EDGE) shouldBe 8.0
        }
    })
