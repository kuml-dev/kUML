package dev.kuml.render.smil

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain

class SmilEmitterTest :
    StringSpec({

        val emitter = SmilEmitter()

        fun singleTimeline(anim: SmilAnimation): SmilTimeline = SmilTimeline(listOf(anim))

        "every emitted animate has begin, dur and attributeName attributes" {
            val timeline =
                singleTimeline(
                    SmilAnimation.Animate(
                        elementId = "el1",
                        attribute = "opacity",
                        from = "0",
                        to = "1",
                        beginMs = 100,
                        durationMs = 600,
                    ),
                )
            val fragment = emitter.renderElements(timeline)
            fragment shouldContain "begin=\"100ms\""
            fragment shouldContain "dur=\"600ms\""
            fragment shouldContain "attributeName=\"opacity\""
        }

        "AnimateTransform emits animateTransform with type, from/to, attributeName, begin and dur" {
            val timeline =
                singleTimeline(
                    SmilAnimation.AnimateTransform(
                        elementId = "t1",
                        type = TransformType.SCALE,
                        from = "1 1",
                        to = "1.15 1.15",
                        beginMs = 200,
                        durationMs = 600,
                    ),
                )
            val fragment = emitter.renderElements(timeline)
            fragment shouldContain "<animateTransform"
            fragment shouldContain "attributeName=\"transform\""
            fragment shouldContain "type=\"scale\""
            fragment shouldContain "from=\"1 1\""
            fragment shouldContain "to=\"1.15 1.15\""
            fragment shouldContain "begin=\"200ms\""
            fragment shouldContain "dur=\"600ms\""
        }

        "AnimateMotion emits animateMotion with path d" {
            val timeline =
                singleTimeline(
                    SmilAnimation.AnimateMotion(
                        elementId = "tok1",
                        path = "M 0 0 L 100 100",
                        beginMs = 600,
                        durationMs = 600,
                    ),
                )
            val fragment = emitter.renderElements(timeline)
            fragment shouldContain "<animateMotion"
            fragment shouldContain "path=\"M 0 0 L 100 100\""
        }

        "Set emits set element with attributeName and to" {
            val timeline =
                singleTimeline(
                    SmilAnimation.Set(
                        elementId = "el2",
                        attribute = "visibility",
                        to = "hidden",
                        beginMs = 0,
                        durationMs = 600,
                    ),
                )
            val fragment = emitter.renderElements(timeline)
            fragment shouldContain "<set"
            fragment shouldContain "attributeName=\"visibility\""
            fragment shouldContain "to=\"hidden\""
        }

        "Fill emits animate with attributeName fill and NEVER animateColor" {
            val timeline =
                singleTimeline(
                    SmilAnimation.Fill(
                        elementId = "node1",
                        color = "#ffd54a",
                        beginMs = 0,
                        durationMs = 600,
                    ),
                )
            val fragment = emitter.renderElements(timeline)
            fragment shouldContain "<animate"
            fragment shouldContain "attributeName=\"fill\""
            fragment shouldContain "to=\"#ffd54a\""
            fragment shouldNotContain "animateColor"
        }

        "Fill with fromColor emits from attribute for flicker-free replay" {
            val timeline =
                singleTimeline(
                    SmilAnimation.Fill(
                        elementId = "node2",
                        color = "#ffd54a",
                        beginMs = 0,
                        durationMs = 600,
                        fromColor = "white",
                    ),
                )
            val fragment = emitter.renderElements(timeline)
            fragment shouldContain "from=\"white\""
            fragment shouldContain "to=\"#ffd54a\""
            fragment shouldContain "attributeName=\"fill\""
        }

        "Fill without fromColor omits from attribute" {
            val timeline =
                singleTimeline(
                    SmilAnimation.Fill(
                        elementId = "node3",
                        color = "#ffd54a",
                        beginMs = 0,
                        durationMs = 600,
                    ),
                )
            val fragment = emitter.renderElements(timeline)
            fragment shouldNotContain "from=\""
            fragment shouldContain "to=\"#ffd54a\""
        }

        "output contains no animateColor substring" {
            // Cross-check all animation types — none should produce animateColor
            val timeline =
                SmilTimeline(
                    listOf(
                        SmilAnimation.Fill("n1", "#ff0000", 0, 600),
                        SmilAnimation.Animate("n2", "opacity", "0", "1", 600, 600),
                        SmilAnimation.AnimateTransform("n3", TransformType.TRANSLATE, "0 0", "50 0", 1200, 600),
                        SmilAnimation.AnimateMotion("n4", "M 0 0 L 10 10", 1800, 600),
                        SmilAnimation.Set("n5", "display", "inline", 2400, 600),
                    ),
                )
            val fragment = emitter.renderElements(timeline)
            fragment shouldNotContain "animateColor"
        }

        "injected SMIL appears before closing svg tag" {
            val svg = "<svg id=\"root\"><rect id=\"n1\"/></svg>"
            val timeline =
                singleTimeline(
                    SmilAnimation.Fill("n1", "#ffd54a", 0, 600),
                )
            val result = emitter.inject(svg, timeline)
            result shouldEndWith "</svg>"
            // The SMIL fragment should appear before </svg>
            val smilIndex = result.indexOf("<animate")
            val closeIndex = result.lastIndexOf("</svg>")
            (smilIndex < closeIndex) shouldBe true
        }

        "inject twice in ANIMATED mode accumulates animations — second call does not destroy first" {
            val svg = "<svg><rect id=\"n1\"/><rect id=\"n2\"/></svg>"
            val first =
                singleTimeline(SmilAnimation.Fill("n1", "#ffd54a", 0, 600))
            val second =
                singleTimeline(SmilAnimation.Fill("n2", "#ff0000", 600, 600))
            val afterFirst = emitter.inject(svg, first)
            val afterSecond = emitter.inject(afterFirst, second)
            // Both animations must be present — inject is additive in ANIMATED mode
            afterSecond shouldContain "xlink:href=\"#n1\""
            afterSecond shouldContain "xlink:href=\"#n2\""
            afterSecond shouldEndWith "</svg>"
        }

        "inject adds xmlns:xlink namespace to svg root for browser SMIL compatibility" {
            val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect id=\"n1\"/></svg>"
            val timeline = singleTimeline(SmilAnimation.Fill("n1", "#ffd54a", 0, 600))
            val result = emitter.inject(svg, timeline)
            result shouldContain "xmlns:xlink=\"http://www.w3.org/1999/xlink\""
        }

        "inject does not duplicate xmlns:xlink if already present" {
            val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"><rect id=\"n1\"/></svg>"
            val timeline = singleTimeline(SmilAnimation.Fill("n1", "#ffd54a", 0, 600))
            val result = emitter.inject(svg, timeline)
            val count = result.split("xmlns:xlink").size - 1
            count shouldBe 1
        }

        "all emitted SMIL elements reference targets via xlink:href not bare href" {
            val timeline =
                SmilTimeline(
                    listOf(
                        SmilAnimation.Fill("n1", "#ffd54a", 0, 600),
                        SmilAnimation.Animate("n2", "opacity", "0", "1", 600, 600),
                        SmilAnimation.AnimateTransform("n3", TransformType.TRANSLATE, "0 0", "50 0", 1200, 600),
                        SmilAnimation.AnimateMotion("n4", "M 0 0 L 10 10", 1800, 600),
                        SmilAnimation.Set("n5", "display", "inline", 2400, 600),
                    ),
                )
            val fragment = emitter.renderElements(timeline)
            fragment shouldContain "xlink:href="
            fragment shouldNotContain " href="
        }
    })
