package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection.In
import dev.kuml.sysml2.ConstraintParameterDirection.Out
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.dsl.pin
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Nacharbeit 84: parallel nested bindings keep a readable lane pitch (12 px between vertical columns, 16 px between
 * horizontal lanes) and a pin stub longer than an arrowhead. Geometry only, no font metrics.
 */
class Sysml2ParLanePitchSvgTest :
    StringSpec({
        "parallel bindings keep lane pitch, stub length and one label each" {
            val model =
                sysml2Model(name = "LanePitch") {
                    val real = attributeDef(name = "Real")
                    val outer =
                        constraintDef(
                            name = "Outer",
                            expression = "o = f(i1, i2, i3, i4)",
                            parameters =
                                listOf("i1", "i2", "i3", "i4").map { ConstraintParameter(name = it, typeId = "Real", direction = In) } +
                                    ConstraintParameter(name = "o1", typeId = "Real", direction = Out),
                        )
                    val a =
                        constraintDef(
                            name = "StageA",
                            expression = "a_out = a1 + a2",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "a1", typeId = "Real", direction = In),
                                    ConstraintParameter(name = "a2", typeId = "Real", direction = In),
                                    ConstraintParameter(name = "a_out", typeId = "Real", direction = Out),
                                ),
                            parent = outer,
                        )
                    val b =
                        constraintDef(
                            name = "StageB",
                            expression = "b_out = b1 + b2 + b3",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "b1", typeId = "Real", direction = In),
                                    ConstraintParameter(name = "b2", typeId = "Real", direction = In),
                                    ConstraintParameter(name = "b3", typeId = "Real", direction = In),
                                    ConstraintParameter(name = "b_out", typeId = "Real", direction = Out),
                                ),
                            parent = outer,
                        )
                    bind(name = "i1_a1", source = outer.pin("i1"), target = a.pin("a1"))
                    bind(name = "i2_a2", source = outer.pin("i2"), target = a.pin("a2"))
                    bind(name = "i3_b2", source = outer.pin("i3"), target = b.pin("b2"))
                    bind(name = "i4_b3", source = outer.pin("i4"), target = b.pin("b3"))
                    bind(name = "a_to_b1", source = a.pin("a_out"), target = b.pin("b1"))
                    bind(name = "b_to_o1", source = b.pin("b_out"), target = outer.pin("o1"))
                    parDiagram(name = "Lane pitch") {
                        include(definition = outer)
                        include(definition = a)
                        include(definition = b)
                        include(definition = real)
                    }
                }
            val diagram = model.diagrams.filterIsInstance<ParDiagram>().single()
            val graph =
                Sysml2LayoutBridge.toLayoutGraph(
                    model = model,
                    diagram = diagram,
                    sizeProvider = Sysml2LayoutBridge.parContentAwareSizeProvider(model),
                )
            val layout =
                LayoutSpecRunner
                    .layout(
                        engine = ElkLayoutEngine(),
                        graph = graph,
                        hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = diagram),
                        spec = diagram.layout,
                        kind = SpecDiagramKind.SYSML2_PAR,
                    ).result
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = layout, theme = PlainTheme())

            val labels =
                Regex("""<g class="kuml-edge-label"[^>]*>(.*?)</g>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(svg)
                    .flatMap { g -> Regex("""<text[^>]*>([^<]*)</text>""").findAll(g.groupValues[1]).map { it.groupValues[1].trim() } }
                    .toList()
            for (name in listOf("i1_a1", "i2_a2", "i3_b2", "i4_b3", "a_to_b1", "b_to_o1")) labels.count { it == name } shouldBe 1

            val lines = binds(svg)
            (lines.size >= 6) shouldBe true
            val vertical = mutableListOf<Seg>()
            val horizontal = mutableListOf<Seg>()
            for ((id, pts) in lines) {
                for (i in 0 until pts.size - 1) {
                    val (p, q) = pts[i] to pts[i + 1]
                    val last = i == pts.size - 2
                    if (last) {
                        // stub in front of an inner (child) pin; the owner pin end of a binding is not part of this lane plan
                        val len = abs(q.first - p.first) + abs(q.second - p.second)
                        if (len >= 1.0 && id.split("::")[2] != "Outer") (len + 0.01).shouldBeGreaterThanOrEqual(12.0)
                    }
                    if (i == 0 || last) continue
                    if (abs(p.first - q.first) < 0.01 &&
                        abs(p.second - q.second) > 1.0
                    ) {
                        vertical += Seg(id = id, at = p.first, from = min(p.second, q.second), to = max(p.second, q.second))
                    }
                    if (abs(p.second - q.second) < 0.01 &&
                        abs(p.first - q.first) > 1.0
                    ) {
                        horizontal += Seg(id = id, at = p.second, from = min(p.first, q.first), to = max(p.first, q.first))
                    }
                }
            }
            checkPitch(segs = vertical, pitch = 12.0)
            checkPitch(segs = horizontal, pitch = 16.0)
        }
    })

private data class Seg(
    val id: String,
    val at: Double,
    val from: Double,
    val to: Double,
)

private fun checkPitch(
    segs: List<Seg>,
    pitch: Double,
) {
    for (i in segs.indices) {
        for (j in i + 1 until segs.size) {
            val a = segs[i]
            val b = segs[j]
            val overlap = min(a.to, b.to) - max(a.from, b.from) > 1.0
            if (a.id != b.id && overlap && abs(a.at - b.at) > 0.01) abs(a.at - b.at).shouldBeGreaterThanOrEqual(pitch - 0.01)
        }
    }
}

private fun nums(text: String): List<Double> = Regex("""-?\d+(?:\.\d+)?""").findAll(text).map { it.value.toDouble() }.toList()

private fun binds(svg: String): Map<String, List<Pair<Double, Double>>> {
    val out = LinkedHashMap<String, List<Pair<Double, Double>>>()
    for (m in Regex("""<g id="(binding:[^"]+)">\s*<path d="([^"]+)" class="kuml-edge"""").findAll(svg)) {
        out[m.groupValues[1]] =
            Regex("([MLA])([^MLA]*)")
                .findAll(m.groupValues[2])
                .map { nums(it.groupValues[2]) }
                .filter { it.size >= 2 }
                .map {
                    it[it.size - 2] to
                        it[it.size - 1]
                }.toList()
    }
    for (m in Regex("""<g id="(binding:[^"]+)">\s*<polyline points="([^"]+)"[^>]*kuml-edge""").findAll(svg)) {
        out[m.groupValues[1]] = nums(m.groupValues[2]).chunked(2).map { it[0] to it[1] }
    }
    return out
}
