package dev.kuml.render.smil

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SmilTimelineTest :
    StringSpec({

        fun fillAt(
            elementId: String,
            beginMs: Long,
            durationMs: Long,
        ) = SmilAnimation.Fill(elementId, "#ff0000", beginMs, durationMs)

        // ── totalDurationMs ───────────────────────────────────────────────────

        "totalDurationMs on empty timeline is 0" {
            SmilTimeline.EMPTY.totalDurationMs shouldBe 0L
        }

        "totalDurationMs on single entry is begin + duration" {
            val timeline = SmilTimeline(listOf(fillAt("n1", beginMs = 200L, durationMs = 600L)))
            timeline.totalDurationMs shouldBe 800L
        }

        "totalDurationMs on multi-entry timeline equals maximum end time" {
            // entry 0: 0..600, entry 1: 400..1200, entry 2: 800..1400 → max = 1400
            val timeline =
                SmilTimeline(
                    listOf(
                        fillAt("n0", beginMs = 0L, durationMs = 600L),
                        fillAt("n1", beginMs = 400L, durationMs = 800L),
                        fillAt("n2", beginMs = 800L, durationMs = 600L),
                    ),
                )
            timeline.totalDurationMs shouldBe 1400L
        }

        "totalDurationMs selects later-ending entry even when its begin is earlier" {
            // n0: begin=0, dur=2000 → end=2000; n1: begin=1000, dur=500 → end=1500
            val timeline =
                SmilTimeline(
                    listOf(
                        fillAt("n0", beginMs = 0L, durationMs = 2000L),
                        fillAt("n1", beginMs = 1000L, durationMs = 500L),
                    ),
                )
            timeline.totalDurationMs shouldBe 2000L
        }

        // ── shiftedBy ─────────────────────────────────────────────────────────

        "shiftedBy shifts all begin times by the given offset" {
            val original =
                SmilTimeline(
                    listOf(
                        fillAt("n0", beginMs = 0L, durationMs = 600L),
                        fillAt("n1", beginMs = 600L, durationMs = 600L),
                        SmilAnimation.AnimateTransform(
                            elementId = "t1",
                            type = TransformType.SCALE,
                            from = "1 1",
                            to = "1.15 1.15",
                            beginMs = 1200L,
                            durationMs = 600L,
                        ),
                    ),
                )
            val shifted = original.shiftedBy(1000L)
            shifted.animations[0].beginMs shouldBe 1000L
            shifted.animations[1].beginMs shouldBe 1600L
            shifted.animations[2].beginMs shouldBe 2200L
        }

        "shiftedBy preserves durationMs unchanged" {
            val original =
                SmilTimeline(
                    listOf(fillAt("n0", beginMs = 0L, durationMs = 500L)),
                )
            val shifted = original.shiftedBy(300L)
            shifted.animations[0].durationMs shouldBe 500L
        }

        "shiftedBy with zero offset returns equivalent timeline" {
            val original =
                SmilTimeline(
                    listOf(fillAt("n0", beginMs = 100L, durationMs = 400L)),
                )
            val shifted = original.shiftedBy(0L)
            shifted.animations[0].beginMs shouldBe 100L
        }

        "shiftedBy covers all SmilAnimation subtypes" {
            val original =
                SmilTimeline(
                    listOf(
                        SmilAnimation.Animate("el1", "opacity", "0", "1", beginMs = 0L, durationMs = 300L),
                        SmilAnimation.AnimateTransform(
                            "t1",
                            TransformType.TRANSLATE,
                            "0 0",
                            "50 0",
                            beginMs = 300L,
                            durationMs = 300L,
                        ),
                        SmilAnimation.AnimateMotion("tok1", "M 0 0 L 10 10", beginMs = 600L, durationMs = 300L),
                        SmilAnimation.Set("el2", "visibility", "visible", beginMs = 900L, durationMs = 300L),
                        SmilAnimation.Fill("n1", "#ff0000", beginMs = 1200L, durationMs = 300L),
                    ),
                )
            val shifted = original.shiftedBy(100L)
            shifted.animations[0].beginMs shouldBe 100L
            shifted.animations[1].beginMs shouldBe 400L
            shifted.animations[2].beginMs shouldBe 700L
            shifted.animations[3].beginMs shouldBe 1000L
            shifted.animations[4].beginMs shouldBe 1300L
        }

        // ── scaledBy ──────────────────────────────────────────────────────────

        "scaledBy 2x halves all begin and duration values" {
            val original =
                SmilTimeline(
                    listOf(fillAt("n0", beginMs = 600L, durationMs = 600L)),
                )
            val scaled = original.scaledBy(SpeedFactor(2.0))
            scaled.animations[0].beginMs shouldBe 300L
            scaled.animations[0].durationMs shouldBe 300L
        }

        "scaledBy 0.5 (slow-motion) doubles all begin and duration values" {
            val original =
                SmilTimeline(
                    listOf(fillAt("n0", beginMs = 600L, durationMs = 400L)),
                )
            val scaled = original.scaledBy(SpeedFactor(0.5))
            scaled.animations[0].beginMs shouldBe 1200L
            scaled.animations[0].durationMs shouldBe 800L
        }

        "scaledBy 1.0 leaves timeline unchanged" {
            val original =
                SmilTimeline(
                    listOf(fillAt("n0", beginMs = 300L, durationMs = 500L)),
                )
            val scaled = original.scaledBy(SpeedFactor.DEFAULT)
            scaled.animations[0].beginMs shouldBe 300L
            scaled.animations[0].durationMs shouldBe 500L
        }

        "scaledBy preserves animation subtype" {
            val original =
                SmilTimeline(
                    listOf(
                        SmilAnimation.AnimateTransform(
                            "t1",
                            TransformType.SCALE,
                            "1 1",
                            "1.15 1.15",
                            beginMs = 600L,
                            durationMs = 600L,
                        ),
                    ),
                )
            val scaled = original.scaledBy(SpeedFactor(2.0))
            scaled.animations[0].shouldBeInstanceOf<SmilAnimation.AnimateTransform>()
            scaled.animations[0].beginMs shouldBe 300L
        }

        "scaledBy slow-motion totalDurationMs is greater than original" {
            val original =
                SmilTimeline(
                    listOf(fillAt("n0", beginMs = 0L, durationMs = 1000L)),
                )
            val originalTotal = original.totalDurationMs
            val slowedTotal = original.scaledBy(SpeedFactor(0.5)).totalDurationMs
            slowedTotal shouldBeGreaterThan originalTotal
        }
    })
