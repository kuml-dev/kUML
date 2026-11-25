@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory

/**
 * PAL wave L: the transition labels of the real "Doc 55" state machine (classpath fixture
 * `sysml2-stm-k/55-einfrieren-stm.kuml.kts`) sit beside their own line, measured on the rendered SVG of the production pipeline
 * (every edge, straight `<line>` edges as well as `<path>` polylines):
 *
 *  * a the label rectangle (2 px larger) crosses no segment of its own polyline,
 *  * b its distance to its own line is at most 18 px,
 *  * c the rectangle (4 px larger) crosses no foreign edge,
 *  * d no foreign edge is as close to the label as its own line.
 *
 * PAL Welle O: all eight labels, T55 and T56 included, now satisfy a to d and two more geometric rules: e the label rectangle
 * (2 px larger) overlaps no state rectangle, f no two label rectangles (2 px larger) overlap. The render reports no
 * KUML-LAYOUT-W013 any more. Two changes are in place: the narrowest label variant (the name `T55:` alone on the first line, 70.8 x 90 px)
 * is now also offered when `labelMaxWidth` is small (this fixture: 130 px; before, the regular narrowest variant was already clamped
 * at the longest word and the name-alone variant was never offered), and a route whose label still finds no position gets its short
 * jog slid inside the channel (`StmJogSlide`), which gives the own segment up to 123 px instead of 90 px. In this fixture the first change
 * already leaves no label unresolved, so the slide does not take effect here (the routes of T55 and T56 are unchanged); it is covered
 * by its own tests.
 *
 * Only geometry, no fonts and no pixel or byte comparison.
 */
class Doc55StmLabelInvariantsTest :
    FunSpec({
        timeout = 300_000L

        val fixture = "/sysml2-stm-k/55-einfrieren-stm.kuml.kts"
        val t55 = "transition:Verzögerung Freigabe ausstehend::Eingefroren PAL"
        val t56 = "transition:Verzögerung Freigabe ausstehend::Eingefroren PAL und Mixing"

        val edgeRe = Regex("""<g id="(transition:[^"]+)">\s*<path d="([^"]*)" class="kuml-edge"""")
        val lineRe =
            Regex(
                """<g id="(transition:[^"]+)">\s*<line x1="([-0-9.]+)" y1="([-0-9.]+)" x2="([-0-9.]+)" y2="([-0-9.]+)" class="kuml-edge"""",
            )
        val labelRe =
            Regex(
                """<g class="kuml-edge-label" data-edge-id="([^"]*)" data-label-id="[^"]*" data-label-rect="([-0-9.]+) ([-0-9.]+) ([-0-9.]+) ([-0-9.]+)"""",
            )

        fun edges(svg: String): Map<String, List<Pair<Double, Double>>> =
            edgeRe.findAll(svg).associate { it.groupValues[1] to SvgGeometry.pathPoints(it.groupValues[2]) } +
                lineRe.findAll(svg).associate {
                    val v = it.groupValues
                    v[1] to listOf(v[2].toDouble() to v[3].toDouble(), v[4].toDouble() to v[5].toDouble())
                }

        fun labels(svg: String): Map<String, SvgRect> =
            labelRe.findAll(svg).associate {
                val v = it.groupValues
                v[1] to SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble())
            }

        // The state rectangles (`<rect ... class="kuml-class">` of the node groups; notes are paths, pseudostates circles).
        fun stateRects(svg: String): Map<String, SvgRect> =
            SvgGeometry
                .nodes(svg)
                .mapNotNull { n ->
                    Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)"[^>]*class="kuml-class"""")
                        .find(n.body)
                        ?.let { m -> n.id to SvgRect(n.tx, n.ty, m.groupValues[1].toDouble(), m.groupValues[2].toDouble()) }
                }.toMap()

        fun grow(
            r: SvgRect,
            m: Double,
        ) = SvgRect(r.x - m, r.y - m, r.w + 2 * m, r.h + 2 * m)

        fun render(): String {
            val script =
                Doc55StmLabelInvariantsTest::class.java
                    .getResourceAsStream(fixture)!!
                    .bufferedReader()
                    .readText()
            val r = VaultExampleRenderer.render(script = script)
            r.error shouldBe null
            return r.svg!!
        }

        fun problems(
            svg: String,
            ids: Collection<String>,
            strict: Boolean,
        ): List<String> {
            val ln = edges(svg)
            val ls = labels(svg)
            val states = stateRects(svg)
            val out = ArrayList<String>()
            for (id in ids) {
                val r =
                    ls[id] ?: run {
                        out += "$id: no label"
                        continue
                    }
                val own = ln.getValue(id)
                val own2 = SvgGeometry.distance(own, grow(r, 2.0))
                val d = SvgGeometry.distance(own, r)
                val foreign =
                    ln.filterKeys { it != id }.mapValues { SvgGeometry.distance(it.value, r) }
                val nearest = foreign.minByOrNull { it.value }
                val info =
                    "$id rect=$r own=${"%.1f".format(d)} nearestForeign=${nearest?.key}:${"%.1f".format(nearest?.value ?: -1.0)}"
                if (own2 <= 0.01) out += "$info: crosses its own line (a)"
                if (d > 18.0 + 0.01) out += "$info: too far from its own line (b)"
                if (!strict) continue
                states.forEach { (name, rect) ->
                    if (grow(r, 2.0).overlaps(rect)) out += "$info: overlaps the state $name (e)"
                }
                ls.filterKeys { it != id }.forEach { (other, rect) ->
                    if (grow(r, 2.0).overlaps(grow(rect, 2.0))) out += "$info: overlaps the label $other (f)"
                }
                if (foreign.any { SvgGeometry.distance(ln.getValue(it.key), grow(r, 4.0)) <= 0.01 }) {
                    out +=
                        "$info: crosses a foreign edge (c)"
                }
                if (nearest != null && nearest.value <= d) out += "$info: foreign edge as close as the own one (d)"
            }
            return out
        }

        test("every label of the diagram sees all its edges (straight and polyline)") {
            val svg = render()
            edges(svg).size shouldBe 8
            labels(svg).size shouldBe 8
        }

        test("all eight labels sit beside their own line, clear of every foreign edge, state and label") {
            val svg = render()
            SampleOutput.write(filename = "sysml2-stm-l/55-einfrieren-stm.svg", content = svg)
            val ids = labels(svg).keys.filter { edges(svg).containsKey(it) }
            ids.size shouldBe 8
            ids.containsAll(listOf(t55, t56)) shouldBe true
            problems(svg, ids, strict = true).joinToString("\n") shouldBe ""
        }

        test("the render reports no KUML-LAYOUT-W013") {
            val logger = LoggerFactory.getLogger("dev.kuml.layout.bridge.label") as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            val oldLevel = logger.level
            logger.level = Level.TRACE
            try {
                render()
                val w013 = appender.list.map { it.formattedMessage }.filter { it.contains("W013") }
                w013.joinToString("\n") shouldBe ""
            } finally {
                logger.detachAppender(appender)
                logger.level = oldLevel
            }
        }
    })
