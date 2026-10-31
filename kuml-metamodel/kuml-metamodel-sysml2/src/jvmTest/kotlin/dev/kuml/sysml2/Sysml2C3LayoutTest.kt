package dev.kuml.sysml2

import dev.kuml.sysml2.constraint.Sysml2LayoutHintChecker
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

/** Wave C3 — layout hints: DSL, printer, JSON default, checker. */
class Sysml2C3LayoutTest :
    FunSpec({
        fun sample(): Sysml2Model =
            sysml2Model(name = "L") {
                val a = partDef(name = "A")
                val b = partDef(name = "B")
                val c = partDef(name = "C")
                bdd(name = "D") {
                    include(a)
                    include(b)
                    include(c)
                    layout {
                        direction = Right
                        wrapColumns = 4
                        sameLayer(a, b)
                        first(c)
                    }
                }
            }

        test("DSL stores the spec on the diagram, an empty block stores none") {
            val d = sample().diagrams.single()
            d.layout shouldBe
                Sysml2LayoutSpec(
                    direction = Sysml2LayoutFlow.Right,
                    wrapColumns = 4,
                    sameLayer = listOf(listOf("A", "B")),
                    first = listOf("C"),
                    last = emptyList(),
                )
            sysml2Model(name = "E") { bdd(name = "X") { layout { } } }.diagrams.single().layout shouldBe null
        }

        test("printer emits the layout block in id form; none without hints") {
            val dsl = Sysml2DslPrinter.print(sample())
            dsl shouldContain "layout {"
            dsl shouldContain "direction = Right"
            dsl shouldContain "wrapColumns = 4"
            dsl shouldContain "sameLayerById(\"A\", \"B\")"
            dsl shouldContain "firstById(\"C\")"
            Sysml2DslPrinter.print(sysml2Model(name = "P") { bdd(name = "X") }) shouldNotContain "layout {"
        }

        test("JSON: round-trips; a diagram without layout keeps the old shape") {
            val m = sample()
            Json.decodeFromString<Sysml2Model>(Json.encodeToString(Sysml2Model.serializer(), m)) shouldBe m
            val old = """{"name":"D","elementIds":["A"]}"""
            val d = Json.decodeFromString(BdDiagram.serializer(), old)
            d.layout shouldBe null
            Json.encodeToString(BdDiagram.serializer(), d) shouldBe old
        }

        fun codes(layout: Sysml2LayoutSpec): List<Int> {
            val m = sysml2Model(name = "X") { partDef(name = "A") }
            return Sysml2LayoutHintChecker
                .check(
                    model = m,
                    diagram = BdDiagram(name = "D", elementIds = listOf("A"), layout = layout),
                ).map { it.code }
        }

        test("checker: clean spec, then each code") {
            Sysml2LayoutHintChecker.check(model = sample(), diagram = sample().diagrams.single()).shouldBeEmpty()
            codes(Sysml2LayoutSpec(first = listOf("ghost"))) shouldContain 431
            codes(Sysml2LayoutSpec(wrapColumns = 0)) shouldContain 432
            codes(Sysml2LayoutSpec(wrapColumns = 33)) shouldContain 432
            codes(Sysml2LayoutSpec(sameLayer = List(33) { listOf("A") })) shouldContain 434
            codes(Sysml2LayoutSpec(first = listOf("A"), last = listOf("A"))) shouldContain 435
            Sysml2LayoutHintChecker
                .check(model = sample(), diagram = SeqDiagram(name = "S", layout = Sysml2LayoutSpec(wrapColumns = 2)))
                .map { it.code } shouldContain 412
        }

        test("checker 433 (partitioned ACT with a non-Down direction) and 436 (connected elements in one layer)") {
            val act =
                sysml2Model(name = "A") {
                    val p = activityPartition(name = "Lane")
                    val x = actionDef(name = "X")
                    val y = actionDef(name = "Y")
                    controlFlow(name = "f", source = x, target = y)
                    actDiagram(name = "D") {
                        includeById(p.id)
                        include(x)
                        include(y)
                        layout {
                            direction = Right
                            sameLayer(x, y)
                        }
                    }
                }
            val found = Sysml2LayoutHintChecker.check(model = act, diagram = act.diagrams.single()).map { it.code }
            found shouldContain 433
            found shouldContain 436
        }

        test("sameLayer accepts id strings (P-IBD-06) and the new size options survive the DSL") {
            val m =
                sysml2Model(name = "S") {
                    partDef(name = "A")
                    partDef(name = "B")
                    bdd(name = "D") {
                        layout {
                            sameLayer("A", "B")
                            first("A")
                            maxWidth = 1800
                            aspectRatio = 1.6
                        }
                    }
                }
            m.diagrams.single().layout shouldBe
                Sysml2LayoutSpec(
                    sameLayer = listOf(listOf("A", "B")),
                    first = listOf("A"),
                    maxWidth = 1800,
                    aspectRatio = 1.6,
                )
            Sysml2DslPrinter.print(m) shouldContain "maxWidth = 1800"
        }

        test("checker 437 rejects out-of-range maxWidth and non-finite aspectRatio; 431 names the option") {
            codes(Sysml2LayoutSpec(maxWidth = 50)) shouldContain 437
            codes(Sysml2LayoutSpec(aspectRatio = Double.NaN)) shouldContain 437
            codes(Sysml2LayoutSpec(aspectRatio = 99.0)) shouldContain 437
            codes(Sysml2LayoutSpec(maxWidth = 1800, aspectRatio = 1.6)).shouldBeEmpty()
            val m = sysml2Model(name = "X") { partDef(name = "A") }
            Sysml2LayoutHintChecker
                .check(
                    model = m,
                    diagram = BdDiagram(name = "D", elementIds = listOf("A"), layout = Sysml2LayoutSpec(first = listOf("ghost"))),
                ).single()
                .message shouldContain "Unknown element id 'ghost' in layout.first"
        }
    })
