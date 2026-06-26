package dev.kuml.render.smil

import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SmilTimelineBuilderStmTest :
    StringSpec({

        val builder = SmilTimelineBuilder()
        val ts = "1970-01-01T00:00:00Z"

        "StateEntered produces a Fill animation targeting the vertexId" {
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.StateEntered(seqNo = 0, timestamp = ts, vertexId = "S1"),
                        ),
                )
            val timeline = builder.build(trace)
            timeline.animations shouldHaveSize 1
            val anim = timeline.animations[0].shouldBeInstanceOf<SmilAnimation.Fill>()
            anim.elementId shouldBe "S1"
            anim.color shouldBe BuildOptions().highlightColor
        }

        "TransitionFired produces two opacity Animate animations (dim then restore)" {
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.TransitionFired(
                                seqNo = 0,
                                timestamp = ts,
                                transitionId = "T1",
                                fromVertexId = "S1",
                                toVertexId = "S2",
                            ),
                        ),
                )
            val timeline = builder.build(trace)
            // TransitionFired emits an opacity pulse (dim 1→0.3, restore 0.3→1) not a scale transform.
            // Scale transforms on SVG path/line elements are visually ineffective; opacity is correct.
            timeline.animations shouldHaveSize 2
            val dim = timeline.animations[0].shouldBeInstanceOf<SmilAnimation.Animate>()
            dim.elementId shouldBe "T1"
            dim.attribute shouldBe "opacity"
            dim.from shouldBe "1"
            dim.to shouldBe "0.3"
            val restore = timeline.animations[1].shouldBeInstanceOf<SmilAnimation.Animate>()
            restore.elementId shouldBe "T1"
            restore.attribute shouldBe "opacity"
            restore.from shouldBe "0.3"
            restore.to shouldBe "1"
        }

        "TransitionFired opacity pulse: dim starts at entry begin, restore starts at begin + stepMs/2" {
            val options = BuildOptions(stepMs = 600)
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.TransitionFired(
                                seqNo = 0,
                                timestamp = ts,
                                transitionId = "T1",
                                fromVertexId = "S1",
                                toVertexId = "S2",
                            ),
                        ),
                )
            val timeline = builder.build(trace, options)
            timeline.animations shouldHaveSize 2
            val dim = timeline.animations[0]
            val restore = timeline.animations[1]
            dim.beginMs shouldBe 0L
            dim.durationMs shouldBe 300L
            restore.beginMs shouldBe 300L
            restore.durationMs shouldBe 300L
        }

        "begin times are monotonically increasing across entries" {
            val options = BuildOptions(stepMs = 400)
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.StateEntered(seqNo = 0, timestamp = ts, vertexId = "S1"),
                            TraceEntry.TransitionFired(
                                seqNo = 1,
                                timestamp = ts,
                                transitionId = "T1",
                                fromVertexId = "S1",
                                toVertexId = "S2",
                            ),
                            TraceEntry.StateEntered(seqNo = 2, timestamp = ts, vertexId = "S2"),
                        ),
                )
            val timeline = builder.build(trace, options)
            // StateEntered(0)=1 Fill, TransitionFired(1)=2 Animate, StateEntered(2)=1 Fill → 4 total
            timeline.animations shouldHaveSize 4
            timeline.animations[0].beginMs shouldBe 0L // StateEntered S1
            timeline.animations[1].beginMs shouldBe 400L // TransitionFired T1 dim
            timeline.animations[2].beginMs shouldBe 600L // TransitionFired T1 restore (400 + 400/2)
            timeline.animations[3].beginMs shouldBe 800L // StateEntered S2
        }

        "StateEntered durations equal stepMs" {
            val options = BuildOptions(stepMs = 750)
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.StateEntered(seqNo = 0, timestamp = ts, vertexId = "S1"),
                        ),
                )
            val timeline = builder.build(trace, options)
            timeline.animations shouldHaveSize 1
            timeline.animations[0].durationMs shouldBe 750L
        }

        "TransitionFired durations are stepMs divided by 2 for each opacity phase" {
            val options = BuildOptions(stepMs = 800)
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.TransitionFired(
                                seqNo = 0,
                                timestamp = ts,
                                transitionId = "T1",
                                fromVertexId = "S1",
                                toVertexId = "S2",
                            ),
                        ),
                )
            val timeline = builder.build(trace, options)
            timeline.animations shouldHaveSize 2
            timeline.animations.forEach { it.durationMs shouldBe 400L }
        }

        "empty trace yields SmilTimeline.EMPTY" {
            val trace = TraceFile(entries = emptyList())
            val timeline = builder.build(trace)
            timeline shouldBe SmilTimeline.EMPTY
            timeline.animations.shouldBeEmpty()
        }
    })
