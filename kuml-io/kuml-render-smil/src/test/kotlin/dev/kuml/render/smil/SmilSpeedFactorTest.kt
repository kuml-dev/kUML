package dev.kuml.render.smil

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain

class SmilSpeedFactorTest :
    StringSpec({

        val emitter = SmilEmitter()

        "SpeedFactor 2.0 halves begin and dur in emitted output via scaledBy" {
            val speed = SpeedFactor(2.0)
            val timeline =
                SmilTimeline(
                    listOf(SmilAnimation.Fill("n1", "#ffd54a", beginMs = 600, durationMs = 600)),
                )
            // Speed scaling is applied via SmilTimeline.scaledBy — the emitter always emits
            // timings as-is; passing speed to the emitter would cause double-scaling.
            val fragment = emitter.renderElements(timeline.scaledBy(speed))
            // 600ms / 2.0 = 300ms
            fragment shouldContain "begin=\"300ms\""
            fragment shouldContain "dur=\"300ms\""
        }

        "SpeedFactor default 1.0 leaves timings unchanged" {
            val speed = SpeedFactor.DEFAULT
            val timeline =
                SmilTimeline(
                    listOf(SmilAnimation.Fill("n1", "#ffd54a", beginMs = 1200, durationMs = 800)),
                )
            val fragment = emitter.renderElements(timeline.scaledBy(speed))
            fragment shouldContain "begin=\"1200ms\""
            fragment shouldContain "dur=\"800ms\""
        }

        "SpeedFactor 0.5 (slow-motion) doubles begin and dur in emitted output via scaledBy" {
            val speed = SpeedFactor(0.5)
            val timeline =
                SmilTimeline(
                    listOf(SmilAnimation.Fill("n1", "#ffd54a", beginMs = 400, durationMs = 600)),
                )
            val fragment = emitter.renderElements(timeline.scaledBy(speed))
            // 400ms / 0.5 = 800ms; 600ms / 0.5 = 1200ms
            fragment shouldContain "begin=\"800ms\""
            fragment shouldContain "dur=\"1200ms\""
        }

        "SpeedFactor rejects zero and negative values" {
            shouldThrow<IllegalArgumentException> { SpeedFactor(0.0) }
            shouldThrow<IllegalArgumentException> { SpeedFactor(-1.0) }
            shouldThrow<IllegalArgumentException> { SpeedFactor(-0.001) }
        }

        "double-scaling guard: scaledBy then renderElements does NOT apply speed twice" {
            // SpeedFactor(2.0) applied once via scaledBy must halve the timings exactly once.
            // If the emitter also scaled internally the result would be 600 / 4 = 150ms — wrong.
            val speed = SpeedFactor(2.0)
            val timeline =
                SmilTimeline(
                    listOf(SmilAnimation.Fill("n1", "#ffd54a", beginMs = 600, durationMs = 600)),
                )
            val fragment = emitter.renderElements(timeline.scaledBy(speed))
            // Must be 300ms (single application), not 150ms (double application)
            fragment shouldContain "begin=\"300ms\""
            fragment shouldContain "dur=\"300ms\""
        }
    })
