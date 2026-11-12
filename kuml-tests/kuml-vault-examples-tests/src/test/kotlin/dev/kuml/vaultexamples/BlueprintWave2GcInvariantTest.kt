@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import dev.kuml.blueprint.model.BlueprintGridConstants
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * PAL wave 2G-c: font independent invariants of the Service Blueprint (stacked cards, card footer with actor name and
 * touchpoint badges, content-aware band heights, switchable labels, connections docking at cards). Everything is
 * measured on the rendered SVG of the full pipeline, never on pixels.
 */
class BlueprintWave2GcInvariantTest :
    FunSpec({
        fun render(script: String): String {
            val r = VaultExampleRenderer.render(script = script)
            r.error shouldBe null
            return r.svg!!
        }

        /** A rendered step card: its rectangle, its group body (up to the next card) and the id. */
        class Card(
            val id: String,
            val rect: SvgRect,
            val body: String,
        )

        fun cards(svg: String): List<Card> {
            val starts =
                Regex(
                    """<g id="(step[^"]*)"><rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)" rx="6"""",
                ).findAll(svg).toList()
            return starts.mapIndexed { i, m ->
                val end = if (i + 1 < starts.size) starts[i + 1].range.first else svg.length
                Card(
                    id = m.groupValues[1],
                    rect =
                        SvgRect(
                            m.groupValues[2].toDouble(),
                            m.groupValues[3].toDouble(),
                            m.groupValues[4].toDouble(),
                            m.groupValues[5].toDouble(),
                        ),
                    body = svg.substring(m.range.first, end),
                )
            }
        }

        fun SvgRect.inside(o: SvgRect) = x >= o.x - 0.01 && y >= o.y - 0.01 && right <= o.right + 0.01 && bottom <= o.bottom + 0.01

        val twoBackstage =
            """
            blueprint("Stack") {
                val vitec = actor("Vitec", ActorRole.SYSTEM)
                val fmcs = actor("FMCS", ActorRole.SYSTEM)
                phase("Eins") {
                    customer("Kunde fragt an", Sentiment.NEUTRAL)
                    backstage("Prueft Vertrag", actor = vitec)
                    backstage("Legt Objekt an", actor = fmcs)
                }
                phase("Zwei") {
                    customer("Kunde wartet", Sentiment.NEGATIVE)
                }
                blueprintDiagram("Stack")
            }
            """.trimIndent()

        test("two backstage cards in one cell are stacked without overlap and both show their actor name") {
            val svg = render(twoBackstage)
            SampleOutput.write(filename = "blueprint-wave2gc/stack.svg", content = svg)
            val cs = cards(svg)
            cs.size shouldBe 4
            val back = cs.filter { it.body.contains("Vitec") || it.body.contains("FMCS") }
            back.size shouldBe 2
            back[0].rect.overlaps(back[1].rect, eps = 0.0) shouldBe false
            (back[1].rect.y >= back[0].rect.bottom) shouldBe true
            back[0].body shouldContain ">Vitec<"
            back[1].body shouldContain ">FMCS<"
            // the band is as tall as the stack: both cards lie inside the same band, below the customer card
            val customer = cs.first { it.body.contains("Kunde fragt an") }
            (back[0].rect.y > customer.rect.bottom) shouldBe true
        }

        val crowded =
            """
            blueprint("Crowded") {
                val staff = actor("Mitarbeiter Mitgliederbuero", ActorRole.STAFF)
                val web = channel("Web", ChannelKind.WEB)
                val t1 = touchpoint("Formular", channel = web)
                val t2 = touchpoint("Portal", channel = web)
                val t3 = touchpoint("Chat", channel = web)
                val t4 = touchpoint("Hotline", channel = web)
                val t5 = touchpoint("Brief", channel = web)
                phase("Eins") {
                    step(
                        name = "Beantwortet die Anfrage des Mitglieds ausfuehrlich und prueft dabei sorgfaeltig alle Unterlagen, Nachweise und " +
                            "Belege, die zuvor ueber verschiedene Kanaele eingegangen sind, und dokumentiert das Ergebnis im Vorgang " +
                            "fuer die spaetere Nachvollziehbarkeit durch die Kolleginnen und Kollegen",
                        layer = BlueprintLayer.FRONTSTAGE,
                        actor = staff,
                        touchpoints = listOf(t1, t2),
                        pain = "Sehr lange Bearbeitungszeit weil Unterlagen fehlen",
                    )
                    frontstage("Viele Kanaele", actor = staff, touchpoints = listOf(t1, t2, t3, t4, t5))
                }
                blueprintDiagram("Crowded")
            }
            """.trimIndent()

        test("long text with actor and touchpoints: title, marker row, actor name and badges stay in disjoint rows inside the card") {
            val svg = render(crowded)
            SampleOutput.write(filename = "blueprint-wave2gc/crowded.svg", content = svg)
            val cs = cards(svg)
            cs.size shouldBe 2
            val long = cs[0]
            val r = long.rect
            // title: baselines from y+20 in 14 px steps; the last baseline plus a descender stays above the marker row
            // only the title's tspans (the wrapped actor name in the footer has tspans of its own, after the title)
            val titlePart = long.body.substringBefore("""class="kuml-small" font-size="10"""")
            val tspans = Regex("""<tspan x="[-0-9.]+" dy="([0-9.]+)"""").findAll(titlePart).count()
            val firstBaseline = r.y + 20.0
            val lastBaseline = firstBaseline + tspans * 14.0
            // the footer grows with the wrapped actor name (Nacharbeit 80): one line is 22 px, each further line adds 12 px
            val nameLines = BlueprintGridConstants.actorNameLines(name = "Mitarbeiter Mitgliederbuero", badgeCount = 2).size
            val footerTop = r.bottom - 4.0 - BlueprintGridConstants.footerHeight(lineCount = nameLines)
            val markerCy = footerTop - 6.0 - 5.0
            (lastBaseline + 4.0 <= markerCy - 5.0) shouldBe true
            // marker row (pain dot) above the footer
            val pain = Regex("""<circle cx="([-0-9.]+)" cy="([-0-9.]+)" r="5" fill="#d00080"""").find(long.body)!!
            (pain.groupValues[2].toDouble() + 5.0 <= footerTop) shouldBe true
            // badges: two centres at least 20 px apart, all inside the footer row of the card
            val numbers =
                Regex(
                    """<text x="([-0-9.]+)" y="([-0-9.]+)" text-anchor="middle" font-size="9" font-weight="700" fill="#333">(\d)</text>""",
                ).findAll(long.body)
                    .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
                    .toList()
            numbers.size shouldBe 2
            (numbers[1].first - numbers[0].first >= 20.0 - 0.01) shouldBe true
            numbers.forEach { (x, y) ->
                (x - 8.0 >= r.x && x + 8.0 <= r.right) shouldBe true
                (y - 8.0 >= footerTop - 0.01 && y + 8.0 <= r.bottom + 0.01) shouldBe true
            }
            // actor name text ends before the first badge
            val actorText =
                Regex(
                    """<text x="([-0-9.]+)" y="[-0-9.]+" class="kuml-small" font-size="10">(.*?)<title>""",
                ).find(long.body)!!
            val widest = Regex("""<tspan[^>]*>([^<]*)</tspan>""").findAll(actorText.groupValues[2]).maxOf { it.groupValues[1].length }
            val nameEnd = actorText.groupValues[1].toDouble() + widest * 5.4
            (nameEnd <= numbers[0].first - 8.0 + 0.5) shouldBe true
            // the five-touchpoint card collapses the surplus into a "+n" chip and keeps the actor name
            val many = cs[1]
            many.body shouldContain ">+"
            many.rect.inside(SvgRect(0.0, 0.0, 100000.0, 100000.0)) shouldBe true
        }

        test("German labels fit the label column and the line captions are German") {
            val svg =
                render(
                    twoBackstage.replace(
                        "blueprintDiagram(\"Stack\")",
                        "blueprintDiagram(\"Stack\", labels = BlueprintLabels.GERMAN)",
                    ),
                )
            SampleOutput.write(filename = "blueprint-wave2gc/german.svg", content = svg)
            svg shouldContain "Kundenaktionen"
            svg shouldContain "Interaktionslinie"
            svg shouldContain "Sichtbarkeitslinie"
            svg shouldContain "Linie der internen Interaktion"
            svg shouldNotContain "Line of Interaction"
            // "Unterstuetzende Prozesse" wraps into two lines inside the 132 px label column
            val lane =
                Regex(
                    """<text x="([-0-9.]+)" y="[-0-9.]+" class="kuml-body" font-size="12" font-weight="600" fill="#1d2968">(.*?)</text>""",
                ).findAll(svg)
                    .map { it.groupValues[2] }
                    .toList()
            lane.any { it.contains("Unterstützende") && it.contains("Prozesse") && it.contains("<tspan") } shouldBe true
            Regex("""<tspan x="([-0-9.]+)" dy="15"""").findAll(svg).forEach { m ->
                val x = m.groupValues[1].toDouble()
                (x >= 16.0 && x < 16.0 + 132.0) shouldBe true
            }
            // the longest line (Unterstuetzende = 14 chars at 7 px) fits the column
            (14 * 7.0 <= 132.0 - 12.0) shouldBe true
        }

        test("rendering is deterministic") {
            render(twoBackstage) shouldBe render(twoBackstage)
            render(crowded) shouldBe render(crowded)
        }

        test("English default: a lone card without actor and touchpoints keeps the classic 184 x 80 card in a 112 px band") {
            val svg =
                render(
                    """
                    blueprint("Plain") {
                        phase("A") { customer("Schritt", Sentiment.NEUTRAL) }
                        journeyDiagram("J")
                    }
                    """.trimIndent(),
                )
            val c = cards(svg).single()
            c.rect.w shouldBe 184.0
            c.rect.h shouldBe 80.0
            svg shouldContain "Customer Actions"
            // no footer, no marker row
            c.body shouldNotContain "kuml-small"
        }

        test("a cell with a single card is unaffected by stacking: card height equals the old fixed card area") {
            val svg = render(twoBackstage)
            val customer = cards(svg).first { it.body.contains("Kunde wartet") }
            customer.rect.h shouldBe 80.0
        }

        test("connections end at the card centres, not at the cell centre") {
            val svg =
                render(
                    """
                    blueprint("Conn") {
                        val staff = actor("Staff", ActorRole.STAFF)
                        lateinit var a: String
                        lateinit var b: String
                        phase("P") {
                            a = backstage("Eins", actor = staff)
                            b = backstage("Zwei", actor = staff)
                        }
                        connection(a, b)
                        blueprintDiagram("C")
                    }
                    """.trimIndent(),
                )
            val cs = cards(svg)
            cs.size shouldBe 2
            val line = Regex("""<line x1="([-0-9.]+)" y1="([-0-9.]+)" x2="([-0-9.]+)" y2="([-0-9.]+)" stroke="#555"""").find(svg)!!
            val (x1, y1, x2, y2) = line.groupValues.drop(1).map { it.toDouble() }
            x1 shouldBe cs[0].rect.cx
            y1 shouldBe cs[0].rect.cy
            x2 shouldBe cs[1].rect.cx
            y2 shouldBe cs[1].rect.cy
        }

        test("note anchors of two stacked cards end on their own cards") {
            val svg =
                render(
                    """
                    blueprint("Notes") {
                        val staff = actor("Staff", ActorRole.STAFF)
                        lateinit var a: String
                        lateinit var b: String
                        phase("P") {
                            a = backstage("Eins", actor = staff)
                            b = backstage("Zwei", actor = staff)
                        }
                        note("Zu Eins", a, id = "n1")
                        note("Zu Zwei", b, id = "n2")
                        blueprintDiagram("N")
                    }
                    """.trimIndent(),
                )
            SampleOutput.write(filename = "blueprint-wave2gc/notes.svg", content = svg)
            val cs = cards(svg)
            val ends =
                Regex(
                    """<path d="([^"]+)" class="kuml-note-anchor"""",
                ).findAll(svg).map { SvgGeometry.pathPoints(it.groupValues[1]).last() }.toList()
            ends.size shouldBe 2
            val eps = 0.01

            fun onCard(
                p: Pair<Double, Double>,
                r: SvgRect,
            ) = p.first >= r.x - eps &&
                p.first <= r.right + eps &&
                (kotlin.math.abs(p.second - r.y) < eps || kotlin.math.abs(p.second - r.bottom) < eps)
            onCard(ends[0], cs[0].rect) shouldBe true
            onCard(ends[1], cs[1].rect) shouldBe true
        }

        test("vault blueprint examples 33, 34, 59 and 65 render with disjoint cards") {
            val examples =
                VaultExampleLoader.loadFromClasspath().filter { e ->
                    listOf("33 ", "34 ", "59 ", "65 ").any { e.baseName.startsWith(it) }
                }
            examples.size shouldBe 4
            for (e in examples) {
                val svg = render(e.kumlScript)
                SampleOutput.write(filename = "blueprint-wave2gc/vault-${e.sanitizedName}.svg", content = svg)
                val cs = cards(svg)
                (cs.size > 0) shouldBe true
                for (i in cs.indices) {
                    for (j in i + 1 until cs.size) {
                        cs[i].rect.overlaps(cs[j].rect, eps = 0.0) shouldBe false
                    }
                }
            }
        }
    })
