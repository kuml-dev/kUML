package dev.kuml.io.anim

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe

class FrameBudgetTest :
    FunSpec({
        test("1200ms at 25fps yields 30 frames") {
            val opts = AnimRenderOptions(fps = 25)
            val budget = FrameBudget.compute(1200L, opts)
            budget.frameCount shouldBe 30
            budget.intervalMs shouldBe 40L
            budget.effectiveFps shouldBe 25
            budget.clampedDurationMs shouldBe 1200L
        }

        test("duration is clamped to maxDurationMs (60s → 30s)") {
            val opts = AnimRenderOptions(fps = 25, maxDurationMs = 30_000L)
            val budget = FrameBudget.compute(60_000L, opts)
            budget.clampedDurationMs shouldBe 30_000L
            budget.frameCount shouldBeLessThanOrEqualTo opts.maxFrames
        }

        test("fps is reduced to 12 when 25fps would exceed 500 frames") {
            // 25fps * 25s = 625 frames → exceeds 500, should reduce to 12fps
            val opts = AnimRenderOptions(fps = 25, maxFrames = 500)
            val budget = FrameBudget.compute(25_000L, opts)
            budget.effectiveFps shouldBe 12
            budget.frameCount shouldBeLessThanOrEqualTo 500
        }

        test("frame count is capped at maxFrames") {
            val opts = AnimRenderOptions(fps = 25, maxFrames = 100)
            val budget = FrameBudget.compute(30_000L, opts)
            budget.frameCount shouldBeLessThanOrEqualTo 100
        }

        test("single ms duration produces at least 1 frame") {
            val opts = AnimRenderOptions(fps = 25)
            val budget = FrameBudget.compute(1L, opts)
            budget.frameCount shouldBe 1
        }

        test("interval math is 1000/fps") {
            val opts = AnimRenderOptions(fps = 10)
            val budget = FrameBudget.compute(5000L, opts)
            budget.intervalMs shouldBe 100L
            budget.frameCount shouldBe 50
        }

        test("total duration clamped then frame budget applied") {
            // 35s at 25fps would be 875 frames, but duration clamps to 30s → 750 still > 500
            // So fps drops to 12: 30000/83 ≈ 361 frames
            val opts = AnimRenderOptions(fps = 25, maxFrames = 500, maxDurationMs = 30_000L)
            val budget = FrameBudget.compute(35_000L, opts)
            budget.clampedDurationMs shouldBe 30_000L
            budget.frameCount shouldBeLessThanOrEqualTo 500
        }
    })
