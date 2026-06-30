package dev.kuml.io.svg.uml.smil

import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import dev.kuml.uml.UmlInteraction
import dev.kuml.uml.UmlLifeline
import dev.kuml.uml.UmlMessage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Security tests for [SequenceMessageTimelineBuilder] — guards against post-loop
 * SVG/heap exhaustion.
 *
 * V3.2 — Security fixes: MAX_TOTAL_ANIMATION_STEPS guard
 */
class SequenceMessageTimelineBuilderSecurityTest :
    StringSpec({

        // ── Fixtures ──────────────────────────────────────────────────────────

        val lifeline1 = UmlLifeline(id = "ll1", name = "Client")
        val lifeline2 = UmlLifeline(id = "ll2", name = "Server")

        fun makeInteraction(messageCount: Int): UmlInteraction {
            val messages =
                (1..messageCount).map { seq ->
                    UmlMessage(
                        id = "msg$seq",
                        label = "call$seq()",
                        fromLifelineId = "ll1",
                        toLifelineId = "ll2",
                        sequence = seq,
                    )
                }
            return UmlInteraction(
                id = "seq1",
                name = "Interaction",
                lifelines = listOf(lifeline1, lifeline2),
                messages = messages,
            )
        }

        fun makeLayout(paddingPx: Float = 0f): Map<NodeId, NodeLayout> =
            mapOf(
                NodeId("ll1") to NodeLayout(Rect(Point(50f - paddingPx, 50f - paddingPx), Size(100f, 400f))),
                NodeId("ll2") to NodeLayout(Rect(Point(250f - paddingPx, 50f - paddingPx), Size(100f, 400f))),
            )

        fun makeTrace(messageCount: Int): TraceFile {
            val ts = "1970-01-01T00:00:00Z"
            val entries =
                (1..messageCount).map { seq ->
                    TraceEntry.MessageSent(
                        seqNo = seq.toLong(),
                        timestamp = ts,
                        messageId = "msg$seq",
                        fromLifelineId = "ll1",
                        toLifelineId = "ll2",
                    )
                }
            return TraceFile(entries = entries)
        }

        // ── Verify MAX_TOTAL_ANIMATION_STEPS constant value ───────────────────

        "MAX_TOTAL_ANIMATION_STEPS is 10000" {
            SequenceAnimationContext.MAX_TOTAL_ANIMATION_STEPS shouldBe 10_000L
        }

        // ── Pre-loop entry count guard (existing) ─────────────────────────────

        "build() rejects trace with more than MAX_ANIMATIONS entries" {
            val limit = SequenceAnimationContext.MAX_ANIMATIONS
            val interaction = makeInteraction(limit + 1)
            val trace = makeTrace(limit + 1)
            val context = SequenceAnimationContext(loopCount = 1)

            val ex =
                shouldThrow<IllegalArgumentException> {
                    SequenceMessageTimelineBuilder.build(interaction, makeLayout(), trace, context)
                }
            ex.message shouldContain "exceeds the maximum of ${SequenceAnimationContext.MAX_ANIMATIONS}"
        }

        // ── Post-loop expansion guard (new: MAX_TOTAL_ANIMATION_STEPS) ────────

        "build() rejects entries x loopCount exceeding MAX_TOTAL_ANIMATION_STEPS" {
            // 51 entries x 200 loops = 10,200 > MAX_TOTAL_ANIMATION_STEPS (10,000)
            val entryCount = 51
            val tooManyLoops = 200
            val interaction = makeInteraction(entryCount)
            val trace = makeTrace(entryCount)
            val context = SequenceAnimationContext(loopCount = tooManyLoops)

            val ex =
                shouldThrow<IllegalArgumentException> {
                    SequenceMessageTimelineBuilder.build(interaction, makeLayout(), trace, context)
                }
            ex.message shouldContain "exceeds the maximum of ${SequenceAnimationContext.MAX_TOTAL_ANIMATION_STEPS}"
        }

        "build() rejects LOOP_INFINITE with 51 entries (51 x LOOP_PRACTICAL_MAX=200 = 10200 > 10000)" {
            val entryCount = 51
            val interaction = makeInteraction(entryCount)
            val trace = makeTrace(entryCount)
            // LOOP_INFINITE maps internally to LOOP_PRACTICAL_MAX = 200
            // 51 x 200 = 10,200 > MAX_TOTAL_ANIMATION_STEPS (10,000)
            val context = SequenceAnimationContext(loopCount = SequenceAnimationContext.LOOP_INFINITE)

            val ex =
                shouldThrow<IllegalArgumentException> {
                    SequenceMessageTimelineBuilder.build(interaction, makeLayout(), trace, context)
                }
            ex.message shouldContain "exceeds the maximum of ${SequenceAnimationContext.MAX_TOTAL_ANIMATION_STEPS}"
        }

        "build() accepts entries x loopCount exactly at MAX_TOTAL_ANIMATION_STEPS" {
            // 50 entries x 200 loops = 10,000 == MAX_TOTAL_ANIMATION_STEPS
            val entryCount = 50
            val loops = 200
            val interaction = makeInteraction(entryCount)
            val trace = makeTrace(entryCount)
            val context = SequenceAnimationContext(loopCount = loops)

            val (timeline, dots) = SequenceMessageTimelineBuilder.build(interaction, makeLayout(), trace, context)
            timeline.animations.size.toLong() shouldBeGreaterThan 0L
            dots.size shouldBe entryCount
        }

        "build() accepts LOOP_INFINITE with 50 entries (50 x LOOP_PRACTICAL_MAX=200 = 10000 == MAX_TOTAL_ANIMATION_STEPS)" {
            val entryCount = 50
            val interaction = makeInteraction(entryCount)
            val trace = makeTrace(entryCount)
            val context = SequenceAnimationContext(loopCount = SequenceAnimationContext.LOOP_INFINITE)

            val (timeline, dots) = SequenceMessageTimelineBuilder.build(interaction, makeLayout(), trace, context)
            timeline.animations.size.toLong() shouldBeGreaterThan 0L
            dots.size shouldBe entryCount
        }

        "build() with loopCount=1 does not trigger the post-loop guard regardless of entry count" {
            // MAX_ANIMATIONS is 500, so use 500 entries with loopCount=1 — guard must not fire
            val entryCount = SequenceAnimationContext.MAX_ANIMATIONS
            val interaction = makeInteraction(entryCount)
            val trace = makeTrace(entryCount)
            val context = SequenceAnimationContext(loopCount = 1)

            // Should not throw — the guard only applies when effectiveLoopCount > 1
            val (timeline, _) = SequenceMessageTimelineBuilder.build(interaction, makeLayout(), trace, context)
            timeline.animations.size.toLong() shouldBeGreaterThan 0L
        }
    })
