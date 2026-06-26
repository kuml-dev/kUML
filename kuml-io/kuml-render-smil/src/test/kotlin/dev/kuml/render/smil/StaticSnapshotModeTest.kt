package dev.kuml.render.smil

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain

class StaticSnapshotModeTest :
    StringSpec({

        val emitter = SmilEmitter()

        fun timelineWithFill() =
            SmilTimeline(
                listOf(SmilAnimation.Fill("n1", "#ffd54a", 0, 600)),
            )

        "STRIPPED mode removes all animate tags from svg" {
            val animEl = """<animate xlink:href="#n1" attributeName="fill" to="#ffd54a" begin="0ms" dur="600ms" fill="freeze"/>"""
            val svg = """<svg><rect id="n1"/>$animEl</svg>"""
            val result = emitter.inject(svg, timelineWithFill(), staticSnapshot = StaticSnapshotMode.STRIPPED)
            result shouldNotContain "<animate"
            result shouldNotContain "animateTransform"
            result shouldNotContain "animateMotion"
        }

        "STRIPPED mode removes animateTransform and set tags" {
            val svg = """<svg>
            <rect id="t1"/>
            <animateTransform xlink:href="#t1" attributeName="transform" type="scale" from="1 1" to="1.15 1.15" begin="0ms" dur="600ms" fill="freeze"/>
            <set xlink:href="#t1" attributeName="visibility" to="hidden" begin="600ms" dur="600ms"/>
        </svg>"""
            val result = emitter.inject(svg, SmilTimeline.EMPTY, staticSnapshot = StaticSnapshotMode.STRIPPED)
            result shouldNotContain "<animateTransform"
            result shouldNotContain "<set"
        }

        "STRIPPED mode on already-clean svg is a no-op idempotent" {
            val cleanSvg = "<svg><rect id=\"n1\"/></svg>"
            val result = emitter.inject(cleanSvg, SmilTimeline.EMPTY, staticSnapshot = StaticSnapshotMode.STRIPPED)
            result shouldBe cleanSvg
        }

        "ANIMATED mode adds SMIL elements" {
            val svg = "<svg><rect id=\"n1\"/></svg>"
            val result = emitter.inject(svg, timelineWithFill(), staticSnapshot = StaticSnapshotMode.ANIMATED)
            result shouldContain "<animate"
            result shouldEndWith "</svg>"
        }
    })
