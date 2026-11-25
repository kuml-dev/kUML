@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge.label

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.edge.EdgeLabelKind
import dev.kuml.sysml2.edge.EdgeLabelPart
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun box(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

/** PAL Welle N: the narrowest label variant (the name may stand alone), and W013 reported once per label. Only CHAR_W/LINE_H geometry, no fonts. */
class StmLabelFloorVariantTest :
    FunSpec(body = {
        timeout = 60_000L

        val t55 =
            listOf(
                EdgeLabelPart(text = "T55:", kind = EdgeLabelKind.Name),
                EdgeLabelPart(text = "ANNAHME: Korridor erneut geöffnet", kind = EdgeLabelKind.Trigger),
                EdgeLabelPart(text = "/ Timer verwerfen", kind = EdgeLabelKind.Effect),
            )

        fun texts(lines: List<StmLabelLine>) = lines.map { it.text }

        test("regression: variantWidths of the T55 label is unchanged") {
            StmLabelWrap.variantWidths(t55, 220f) shouldBe listOf(165f, 121f, 88f)
        }

        test("without allowNameAlone the name sticks to the first word (13 characters, 99.6 px box)") {
            val lines = StmLabelWrap.wrap(parts = t55, maxWidthPx = 64.8f, minWordsPerLine = 1)
            lines.first().text shouldBe "T55: ANNAHME:"
            StmLabelWrap.boxSize(lines).width shouldBe 13 * StmLabelWrap.CHAR_W + 2 * StmLabelWrap.PAD
        }

        test("allowNameAlone puts the name alone, one word per line, the glued effect stays together") {
            val lines = StmLabelWrap.wrap(parts = t55, maxWidthPx = 64.8f, minWordsPerLine = 1, allowNameAlone = true)
            texts(lines) shouldBe listOf("T55:", "ANNAHME:", "Korridor", "erneut", "geöffnet", "/ Timer", "verwerfen")
            lines
                .first()
                .parts
                .single()
                .kind shouldBe EdgeLabelKind.Name
            lines.none { it.brokenInWord } shouldBe true
            val size = StmLabelWrap.boxSize(lines)
            size.width shouldBe 9 * StmLabelWrap.CHAR_W + 2 * StmLabelWrap.PAD
            size.height shouldBe 7 * StmLabelWrap.LINE_H + 2 * StmLabelWrap.PAD
        }

        test("allowNameAlone keeps the name attached when name and first word fit the line") {
            val lines = StmLabelWrap.wrap(parts = t55, maxWidthPx = 140f, minWordsPerLine = 1, allowNameAlone = true)
            lines.first().text shouldBe "T55: ANNAHME:"
        }

        test("floorVariantWidth counts the name as a word and the glued effect as one unit") {
            val w = StmLabelWrap.floorVariantWidth(t55, 220f)!!
            // Longest unit is `verwerfen` (9 characters): the wrap holds exactly 9 characters.
            StmLabelWrap.maxChars(w) shouldBe 9
            (w < 88f) shouldBe true
        }

        test("floorVariantWidth is null when the floor is not narrower than the narrowest variant") {
            val parts =
                listOf(EdgeLabelPart("T1:", EdgeLabelKind.Name), EdgeLabelPart("Sicherheitsuebergangsbedingung", EdgeLabelKind.Trigger))
            StmLabelWrap.floorVariantWidth(parts, 220f) shouldBe null
        }

        test("variantWraps appends the floor variant last and keeps the older variants as they were") {
            val normal = StmLabelWrap.wrap(t55, 220f)
            val old =
                StmLabelWrap
                    .variantWidths(t55, 220f)
                    .map { StmLabelWrap.wrap(parts = t55, maxWidthPx = it, minWordsPerLine = 1) }
            val offered = StmLabelWrap.variantWraps(t55, 220f, StmLabelWrap.boxSize(normal).width, longestSegmentPx = 200f)
            texts(offered.last()).first() shouldBe "T55:"
            // Every older variant that passed the filters is still there, in the same order, before the floor variant.
            val before = offered.dropLast(1).map(::texts)
            before shouldBe old.map(::texts).filter { it in before }
            // The floor variant is the narrowest.
            val widths = offered.map { StmLabelWrap.boxSize(it).width }
            widths shouldBe widths.sortedDescending()
        }

        test("variantWraps drops the floor variant when it is taller than the longest segment allows") {
            val offered = StmLabelWrap.variantWraps(t55, 220f, 214.8f, longestSegmentPx = 40f)
            offered.none { it.first().text == "T55:" && it.size == 7 } shouldBe true
        }

        // A vertical edge at x = 100 between two walls.
        fun corridor(gap: Float) =
            mapOf(
                "left" to box(-300f, 10f, 300f + 100f - gap, 180f),
                "right" to box(100f + gap, 10f, 300f, 180f),
            )

        val route = mapOf("e" to EdgeRoute.Direct(source = Point(100f, 0f), target = Point(100f, 200f)))

        fun plan(
            nodes: Map<String, Rect>,
            reportUnresolved: Boolean = true,
        ): Pair<Sysml2LabelPlanResult, StmLabelSpec> {
            val normal = StmLabelWrap.wrap(t55, 220f)
            val variants =
                StmLabelWrap
                    .variantWraps(t55, 220f, StmLabelWrap.boxSize(normal).width, 400f)
                    .map { StmLabelVariant(lines = it.map(StmLabelLine::text), size = StmLabelWrap.boxSize(it)) }
            val spec = StmLabelSpec(id = "e", lines = normal.map { it.text }, size = StmLabelWrap.boxSize(normal), variants = variants)
            val result =
                Sysml2DiagramLabelPlan.planStmLabels(
                    edges = route,
                    nodes = nodes,
                    composites = emptyList(),
                    labels = listOf(spec),
                    frameTop = 0f,
                    reportUnresolved = reportUnresolved,
                )
            return result to spec
        }

        test("happy path: a corridor only the floor variant fits is solved with the last variant, name alone") {
            val (r, spec) = plan(corridor(gap = 80f))
            r.unresolved shouldBe emptyList()
            r.variantChosen shouldBe mapOf("e" to spec.variants.size - 1)
            r.lines.getValue("e").first() shouldBe "T55:"
            r.labels.getValue("e").clean shouldBe true
        }

        test("a corridor too narrow even for the floor variant stays unresolved") {
            val (r, _) = plan(corridor(gap = 40f))
            r.unresolved shouldBe listOf("e")
        }

        test("stability: a label that already fit keeps its position and wrap when the floor variant is offered") {
            val (withFloor, spec) = plan(emptyMap())
            val without = spec.copy(variants = spec.variants.dropLast(1))
            val r2 =
                Sysml2DiagramLabelPlan.planStmLabels(
                    edges = route,
                    nodes = emptyMap(),
                    composites = emptyList(),
                    labels = listOf(without),
                    frameTop = 0f,
                )
            withFloor.variantChosen shouldBe emptyMap()
            withFloor.labels.getValue("e").rect shouldBe r2.labels.getValue("e").rect
            withFloor.lines shouldBe r2.lines
        }

        test("W013 is reported once per label however often the plan runs, and the planner itself stays quiet") {
            val messages = ArrayList<String>()
            val report = StmUnresolvedReport(sink = { messages += it })
            repeat(4) { report.report(plan(corridor(gap = 40f), reportUnresolved = false).first) }
            messages shouldHaveSize 1
            messages.single() shouldContain "KUML-LAYOUT-W013"
            messages.single() shouldContain "'e'"
        }
    })
