package dev.kuml.render.smil

import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SmilTimelineBuilderActivityTest :
    StringSpec({

        val builder = SmilTimelineBuilder()
        val ts = "1970-01-01T00:00:00Z"

        "TokenPlaced with a resolvable path produces an AnimateMotion" {
            val pathResolver: (String) -> String? = { edgeId -> if (edgeId == "node1") "M 0 0 L 100 100" else null }
            val options = BuildOptions(pathResolver = pathResolver)
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.TokenPlaced(seqNo = 0, timestamp = ts, nodeId = "node1", clock = 0L),
                        ),
                )
            val timeline = builder.build(trace, options)
            timeline.animations shouldHaveSize 1
            timeline.animations[0].shouldBeInstanceOf<SmilAnimation.AnimateMotion>()
        }

        "TokenPlaced without path resolver is skipped (no AnimateMotion)" {
            val options = BuildOptions() // default resolver always returns null
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.TokenPlaced(seqNo = 0, timestamp = ts, nodeId = "edge99", clock = 0L),
                        ),
                )
            val timeline = builder.build(trace, options)
            // no AnimateMotion because resolver returned null
            val motions = timeline.animations.filterIsInstance<SmilAnimation.AnimateMotion>()
            motions shouldHaveSize 0
        }

        "AnimateMotion path equals pathResolver output" {
            val expectedPath = "M 10 20 L 80 90"
            val options = BuildOptions(pathResolver = { expectedPath })
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.TokenPlaced(seqNo = 0, timestamp = ts, nodeId = "e1", clock = 0L),
                        ),
                )
            val timeline = builder.build(trace, options)
            val motion = timeline.animations[0].shouldBeInstanceOf<SmilAnimation.AnimateMotion>()
            motion.path shouldBe expectedPath
        }

        "mixed STM+Activity trace produces both Fill and AnimateMotion entries" {
            val options = BuildOptions(pathResolver = { "M 0 0 L 50 50" })
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.StateEntered(seqNo = 0, timestamp = ts, vertexId = "S1"),
                            TraceEntry.TokenPlaced(seqNo = 1, timestamp = ts, nodeId = "e1", clock = 1L),
                        ),
                )
            val timeline = builder.build(trace, options)
            timeline.animations shouldHaveSize 2
            timeline.animations[0].shouldBeInstanceOf<SmilAnimation.Fill>()
            timeline.animations[1].shouldBeInstanceOf<SmilAnimation.AnimateMotion>()
        }

        "TokenConsumed with resolvable path emits AnimateMotion with consumed suffix" {
            val pathResolver: (String) -> String? = { "M 0 0 L 100 100" }
            val options = BuildOptions(pathResolver = pathResolver)
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.TokenConsumed(seqNo = 0, timestamp = ts, nodeId = "edge1", clock = 0L),
                        ),
                )
            val timeline = builder.build(trace, options)
            timeline.animations shouldHaveSize 1
            val motion = timeline.animations[0].shouldBeInstanceOf<SmilAnimation.AnimateMotion>()
            motion.path shouldBe "M 0 0 L 100 100"
            // element id should carry the consumed suffix to distinguish from TokenPlaced
            motion.elementId shouldBe "${BuildOptions().tokenElementIdPrefix}edge1-consumed"
        }

        "TokenConsumed without resolvable path is skipped" {
            val options = BuildOptions() // default resolver returns null
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.TokenConsumed(seqNo = 0, timestamp = ts, nodeId = "edge99", clock = 0L),
                        ),
                )
            val timeline = builder.build(trace, options)
            timeline.animations shouldHaveSize 0
        }
    })
