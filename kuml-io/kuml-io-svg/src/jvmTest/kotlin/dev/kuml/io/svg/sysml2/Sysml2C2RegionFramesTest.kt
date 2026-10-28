package dev.kuml.io.svg.sysml2

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.InterruptibleRegionDefinition
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Wave C2 — region frame computation: nesting, padding, and bounded behaviour on cycles / deep nesting. */
class Sysml2C2RegionFramesTest :
    StringSpec({
        fun rect(
            x: Float,
            y: Float,
        ) = Rect(origin = Point(x = x, y = y), size = Size(width = 100f, height = 50f))

        "a region frames its members with 16 px padding and 28 px on top when named" {
            val frames =
                computeRegionFrames(
                    regions = listOf(InterruptibleRegionDefinition(id = "r", name = "R")),
                    actions = listOf(ActionDefinition(id = "a", name = "a", interruptibleRegionId = "r")),
                    nodeBounds = mapOf("a" to rect(100f, 100f)),
                )
            val r = frames.single().rect
            r.origin.x shouldBe 84f
            r.origin.y shouldBe 72f
            r.size.width shouldBe 132f
            r.size.height shouldBe 50f + 28f + 16f
        }

        "an unnamed-label region uses plain padding, nested regions are enclosed by their parent" {
            val frames =
                computeRegionFrames(
                    regions =
                        listOf(
                            InterruptibleRegionDefinition(id = "o", name = "O"),
                            InterruptibleRegionDefinition(id = "i", name = "I", showLabel = false, parentRegionId = "o"),
                        ),
                    actions = listOf(ActionDefinition(id = "a", name = "a", interruptibleRegionId = "i")),
                    nodeBounds = mapOf("a" to rect(100f, 100f)),
                )
            val byId = frames.associate { it.region.id to it.rect }
            byId.getValue("i").origin.y shouldBe 84f
            (byId.getValue("o").origin.x < byId.getValue("i").origin.x) shouldBe true
            frames.first().region.id shouldBe "o"
        }

        "a parent cycle and nesting beyond the depth limit terminate without exception" {
            val cyc =
                computeRegionFrames(
                    regions =
                        listOf(
                            InterruptibleRegionDefinition(id = "a", name = "a", parentRegionId = "b"),
                            InterruptibleRegionDefinition(id = "b", name = "b", parentRegionId = "a"),
                        ),
                    actions = listOf(ActionDefinition(id = "n", name = "n", interruptibleRegionId = "a")),
                    nodeBounds = mapOf("n" to rect(0f, 0f)),
                )
            (cyc.size <= 2) shouldBe true
            val deep =
                computeRegionFrames(
                    regions =
                        (0..30).map {
                            InterruptibleRegionDefinition(
                                id = "r$it",
                                name = "r$it",
                                parentRegionId =
                                    if (it ==
                                        0
                                    ) {
                                        null
                                    } else {
                                        "r${it - 1}"
                                    },
                            )
                        },
                    actions = listOf(ActionDefinition(id = "n", name = "n", interruptibleRegionId = "r30")),
                    nodeBounds = mapOf("n" to rect(0f, 0f)),
                )
            (deep.size <= 31) shouldBe true
        }

        "empty regions produce no frame" {
            computeRegionFrames(
                regions = listOf(InterruptibleRegionDefinition(id = "r", name = "R")),
                actions = emptyList(),
                nodeBounds = emptyMap(),
            ) shouldBe emptyList()
        }
    })
