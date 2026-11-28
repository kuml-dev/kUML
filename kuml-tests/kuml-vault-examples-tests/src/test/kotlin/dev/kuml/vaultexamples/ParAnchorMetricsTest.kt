@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec

/** Self test of [ParAnchorMetrics] on synthetic SVGs (no rendering): a positive and a negative case per kind plus the limits. */
class ParAnchorMetricsTest :
    FunSpec({
        fun box(
            id: String,
            x: Double,
            y: Double,
            w: Double,
            h: Double,
        ) = """<g id="$id" transform="translate($x,$y)"><rect width="$w" height="$h" class="kuml-class"/>""" +
            """<rect x="-4" y="20" width="8" height="8" class="kuml-class kuml-par-pin"/></g>"""

        fun edge(
            id: String,
            d: String,
            label: String = "",
        ) = """<g id="$id"><path d="$d" class="kuml-edge"/>$label</g>"""

        fun label(
            x: Double,
            y: Double,
        ) = """<g class="kuml-edge-label" data-label-rect="$x $y 28 19"><text class="kuml-body" x="$x" y="$y">b</text></g>"""

        fun svg(
            nodes: String,
            edges: String,
        ) = """<svg viewBox="0 0 800 600"><g id="nodes">$nodes</g><g id="edges">$edges</g></svg>"""

        // end box A at x=100..300 (y 100..250); the pin is on its left side at (96,124); owner composite O around it.
        val nodes = box("O", 50.0, 50.0, 400.0, 300.0) + box("A", 100.0, 100.0, 200.0, 150.0) + box("F", 600.0, 100.0, 100.0, 100.0)

        fun p1Run(
            dx: Double,
            run: Double,
        ): ParAnchorReport {
            // horizontal run along the top side of A (y = 100 + dx), starting at the pin end point (96,124) via a vertical stub
            val y = 100.0 + dx
            val svg = svg(nodes, edge("e", "M 96 124 L 96 $y L ${96 + run} $y"))
            return ParAnchorMetrics.measure(svg)
        }

        test("P1 limit: a run of 24 px at 3.9 px counts, 23.9 px does not, 4.0 px distance does not") {
            check(p1Run(3.9, 28.0).p1.size == 1) { "24 px run at 3.9 px expected: ${p1Run(3.9, 28.0).p1}" }
            // run along A's top side: x from 100 to 96+run; overlap with the side (x>=100) is run-4
            check(p1Run(3.9, 28.0).p1.single().valuePx == 24.0) { "overlap not 24: ${p1Run(3.9, 28.0).p1}" }
            check(p1Run(3.9, 27.9).p1.isEmpty()) { "23.9 px overlap must not count" }
            check(p1Run(4.0, 40.0).p1.isEmpty()) { "4.0 px distance must not count" }
        }

        test("P1 counts the owner composite side but not a foreign box") {
            val owner = svg(nodes, edge("e", "M 96 124 L 96 80 L 160 80 L 160 124"))
            // runs at y=80, i.e. 30 px from O's top (50) and 20 px from A's top (100): no hit
            check(ParAnchorMetrics.measure(owner).p1.isEmpty()) { "no hit expected" }
            val closeToOwner = svg(nodes, edge("e", "M 96 124 L 96 52 L 160 52 L 160 124"))
            check(ParAnchorMetrics.measure(closeToOwner).p1.any { it.other.startsWith("O:") }) { "owner top side expected" }
            val foreign = svg(nodes, edge("e", "M 96 124 L 80 124 L 80 300 L 650 300 L 650 102 L 560 102"))
            check(ParAnchorMetrics.measure(foreign).p1.none { it.other.startsWith("F:") }) { "foreign box must not count" }
        }

        test("P1 negative: a clean route is not reported") {
            check(ParAnchorMetrics.measure(svg(nodes, edge("e", "M 96 124 L 80 124 L 80 300"))).p1.isEmpty()) { "clean route reported" }
        }

        test("P2 limit: labels 5.9 px apart count, 6.0 px do not") {
            val e1 = edge("e1", "M 0 0 L 10 0", label(100.0, 100.0))
            val near = edge("e2", "M 0 5 L 10 5", label(100.0 + 28.0 + 5.9, 100.0))
            val far = edge("e2", "M 0 5 L 10 5", label(100.0 + 28.0 + 6.0, 100.0))
            check(ParAnchorMetrics.measure(svg(nodes, e1 + near)).p2.size == 1) { "5.9 px expected" }
            check(ParAnchorMetrics.measure(svg(nodes, e1 + far)).p2.isEmpty()) { "6.0 px must not count" }
        }

        test("P3 limit: a label 5.9 px beside its own edge counts, 6.0 px does not, a foreign edge does not") {
            val poly = "M 0 0 L 200 0 L 200 200"
            val near = edge("e1", poly, label(200.0 + 5.9, 50.0))
            val far = edge("e1", poly, label(200.0 + 6.0, 50.0))
            check(ParAnchorMetrics.measure(svg(nodes, near)).p3.size == 1) { "5.9 px expected" }
            check(ParAnchorMetrics.measure(svg(nodes, far)).p3.isEmpty()) { "6.0 px must not count" }
            val foreign = edge("e1", poly) + edge("e2", "M 400 0 L 400 10", label(200.0 + 2.0, 50.0))
            check(ParAnchorMetrics.measure(svg(nodes, foreign)).p3.isEmpty()) { "foreign edge must not count" }
        }
    })
