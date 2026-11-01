@file:Suppress("RequireNamedArguments")

package dev.kuml.sysml2

import dev.kuml.core.model.layout.ActorSide
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.sysml2.constraint.Sysml2LayoutHintChecker
import dev.kuml.sysml2.constraint.Sysml2ReqUcChecker
import dev.kuml.sysml2.constraint.Sysml2ValueChecker
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.edge.BddEdgeAdapter
import dev.kuml.sysml2.edge.Sysml2ArrowHead
import dev.kuml.sysml2.edge.UcEdgeAdapter
import dev.kuml.sysml2.projection.BddEdgeKind
import dev.kuml.sysml2.projection.NBSP
import dev.kuml.sysml2.projection.formatAttributeValue
import dev.kuml.sysml2.projection.sysml2BddBoxContent
import dev.kuml.sysml2.projection.sysml2BddProjection
import dev.kuml.sysml2.units.Pa
import dev.kuml.sysml2.units.Unit
import dev.kuml.sysml2.units.UnitValue
import dev.kuml.sysml2.units.degC
import dev.kuml.sysml2.units.mA
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Wave 2E-b - use-case members, extend condition / extension point, actorSide, units, enumeration, refPart, role labels. */
class Sysml2Wave2EbTest :
    FunSpec({
        test("P-UC-01: a use case that is only declared inside a subject frame is a member of the diagram") {
            val m =
                sysml2Model(name = "M") {
                    val a = actorDef("A")
                    val x = useCaseDef("X")
                    val y = useCaseDef("Y")
                    ucDiagram("D") {
                        include(a)
                        subject("S") {
                            include(x)
                            include(y)
                        }
                        association(a, x)
                    }
                }
            val d = m.diagrams.filterIsInstance<UcDiagram>().single()
            d.elementIds shouldContainExactly listOf("A", "X", "Y")
        }

        test("a use case listed explicitly and in a subject is listed once, explicit ones first") {
            val m =
                sysml2Model(name = "M") {
                    val x = useCaseDef("X")
                    val y = useCaseDef("Y")
                    ucDiagram("D") {
                        include(y)
                        subject("S") {
                            include(x)
                            include(y)
                        }
                    }
                }
            m.diagrams
                .filterIsInstance<UcDiagram>()
                .single()
                .elementIds shouldContainExactly listOf("Y", "X")
        }

        test("extend carries condition and extension point, JSON round-trips, old JSON without them still decodes") {
            val m =
                sysml2Model(name = "M") {
                    val a = useCaseDef("A")
                    val b = useCaseDef("B")
                    ucDiagram("D") {
                        include(a)
                        include(b)
                        extend(a, b, condition = "has voucher", extensionPoint = "discount")
                    }
                }
            val ext =
                m.diagrams
                    .filterIsInstance<UcDiagram>()
                    .single()
                    .extends
                    .single()
            ext.condition shouldBe "has voucher"
            ext.extensionPoint shouldBe "discount"
            val text = Json.encodeToString(m)
            Json.decodeFromString<Sysml2Model>(text) shouldBe m
            val old = """{"id":"extend:A::B","sourceUseCaseId":"A","targetUseCaseId":"B"}"""
            val decoded = Json.decodeFromString<UcExtend>(old)
            decoded.condition shouldBe null
            decoded.extensionPoint shouldBe null
        }

        test("extend condition becomes the second line of the edge label, a long one is shortened") {
            val m =
                sysml2Model(name = "M") {
                    val a = useCaseDef("A")
                    val b = useCaseDef("B")
                    ucDiagram("D") {
                        include(a)
                        include(b)
                        extend(a, b, condition = "x".repeat(300))
                    }
                }
            val d = m.diagrams.filterIsInstance<UcDiagram>().single()
            val md = UcEdgeAdapter(d).metadataFor(d.extends.single().id)!!
            md.stereotype shouldBe "«extend»"
            md.label!!.length shouldBe UcEdgeAdapter.EXTEND_CONDITION_DISPLAY_MAX + 2
            UcEdgeAdapter(d).metadataFor("nothing") shouldBe null
        }

        test("219: an edge to an element that is not shown warns, a subject member counts as shown") {
            val m =
                sysml2Model(name = "M") {
                    val a = actorDef("A")
                    val shown = useCaseDef("Shown")
                    val hidden = useCaseDef("Hidden")
                    ucDiagram("D") {
                        include(a)
                        subject("S") { include(shown) }
                        association(a, shown)
                        association(a, hidden)
                    }
                }
            val issues = Sysml2ReqUcChecker.check(model = m).filter { it.code == 219 }
            issues.size shouldBe 1
            issues.single().elementId shouldBe "assoc:A::Hidden"
        }

        test("220: a too long condition is a hint") {
            val m =
                sysml2Model(name = "M") {
                    val a = useCaseDef("A")
                    val b = useCaseDef("B")
                    ucDiagram("D") {
                        include(a)
                        include(b)
                        extend(a, b, extensionPoint = "p".repeat(80))
                    }
                }
            Sysml2ReqUcChecker.check(model = m).map { it.code } shouldContain 220
        }

        test("actorSide: builder, merge, printer, hint checker 439 outside use-case diagrams") {
            val m =
                sysml2Model(name = "M") {
                    val a = actorDef("A")
                    val p = partDef("P")
                    ucDiagram("U") {
                        include(a)
                        layout { actorSide = ActorSide.Left }
                    }
                    bdd("B") {
                        include(p)
                        layout { actorSide = ActorSide.Right }
                    }
                }
            val uc = m.diagrams.filterIsInstance<UcDiagram>().single()
            uc.layout!!.actorSide shouldBe ActorSide.Left
            LayoutSpec(actorSide = ActorSide.Left).isEmpty shouldBe false
            LayoutSpec(actorSide = ActorSide.Left).mergedWith(LayoutSpec(actorSide = ActorSide.Both)).actorSide shouldBe ActorSide.Both
            LayoutSpec(actorSide = ActorSide.Left).mergedWith(LayoutSpec()).actorSide shouldBe ActorSide.Left
            Sysml2DslPrinter.print(m) shouldContain "actorSide = ActorSide.Left"
            Sysml2LayoutHintChecker.check(model = m, diagram = uc).map { it.code }.contains(439) shouldBe false
            Sysml2LayoutHintChecker
                .check(
                    model = m,
                    diagram = m.diagrams.filterIsInstance<BdDiagram>().single(),
                ).map { it.code } shouldContain
                439
            // old JSON without actorSide decodes
            Json.decodeFromString<LayoutSpec>("""{"wrapColumns":2}""").actorSide shouldBe null
        }

        test("units: Pa, degC and mA exist, any other symbol is accepted ad hoc") {
            (-5).Pa.unit.symbol shouldBe "Pa"
            21.degC.unit.symbol shouldBe "°C"
            4.mA.unit.symbol shouldBe "mA"
            Unit.ALL shouldContain Unit.PASCAL
            Unit(symbol = "bar", longName = "bar", quantityKind = "Pressure").toString() shouldBe "bar"
        }

        test("formatAttributeValue: integral numbers lose .0, the unit comes from the type, NBSP, ASCII minus, locale independent") {
            val saved = java.util.Locale.getDefault()
            try {
                for (loc in listOf(java.util.Locale.GERMANY, java.util.Locale.US, java.util.Locale.forLanguageTag("ar-EG"))) {
                    java.util.Locale.setDefault(loc)
                    formatAttributeValue(raw = "-5.0", typeUnit = "Pa") shouldBe "-5${NBSP}Pa"
                    formatAttributeValue(raw = "-5.0[Pa]", typeUnit = null) shouldBe "-5${NBSP}Pa"
                    formatAttributeValue(raw = "0.25", typeUnit = "A") shouldBe "0.25${NBSP}A"
                    formatAttributeValue(raw = "7", typeUnit = null) shouldBe "7"
                    formatAttributeValue(raw = "Rot", typeUnit = null) shouldBe "Rot"
                }
            } finally {
                java.util.Locale.setDefault(saved)
            }
            ('-'.code) shouldBe 0x2D
        }

        test("a numeric default with a typed unit is shown as 'name : Type = -5 Pa'") {
            val m =
                sysml2Model(name = "M") {
                    val pressure = attributeDef("Raumdruck", unitSymbol = "Pa")
                    partDef("Raum") { attribute("raumdruck", pressure.id, default = -5.0) }
                }
            val room = m.definitions.filterIsInstance<PartDefinition>().single()
            sysml2BddBoxContent(model = m, def = room, projection = null).featureLines shouldContainExactly
                listOf("raumdruck : Raumdruck = -5${NBSP}Pa")
        }

        test("enumerationDef: literals, box lines, default as literal, JSON and printer round-trip") {
            val m =
                sysml2Model(name = "M") {
                    val colour =
                        enumerationDef("Ampelfarbe") {
                            literal("Rot")
                            literal("Gelb")
                            literal("Rot")
                        }
                    partDef("Ampel") { attribute("farbe", colour.id, defaultLiteral = "Rot") }
                }
            val e = m.definitions.filterIsInstance<EnumerationDefinition>().single()
            e.literals shouldContainExactly listOf("Rot", "Gelb")
            sysml2BddBoxContent(model = m, def = e, projection = null).featureLines shouldContainExactly listOf("Rot", "Gelb")
            val ampel = m.definitions.filterIsInstance<PartDefinition>().single()
            sysml2BddBoxContent(model = m, def = ampel, projection = null).featureLines shouldContainExactly
                listOf("farbe : Ampelfarbe = Rot")
            Json.decodeFromString<Sysml2Model>(Json.encodeToString(m)) shouldBe m
            Sysml2DslPrinter.print(m) shouldContain "enumerationDef("
        }

        test("enumeration literal cap") {
            shouldThrow<IllegalArgumentException> {
                sysml2Model(name = "M") { enumerationDef("E") { repeat(70) { literal("L$it") } } }
            }
        }

        test("value checker: 501 unit mismatch, 502 not a literal, 503 number for an enumeration") {
            val m =
                sysml2Model(name = "M") {
                    val pressure = attributeDef("Raumdruck", unitSymbol = "Pa")
                    val colour = enumerationDef("Farbe") { literal("Rot") }
                    partDef("A") {
                        attribute("p", pressure.id, default = UnitValue(value = 1.0, unit = Unit.CELSIUS))
                        attribute("f", colour.id, defaultLiteral = "Blau")
                        attribute("g", colour.id, default = 3.0)
                        attribute("ok", colour.id, defaultLiteral = "Rot")
                    }
                }
            Sysml2ValueChecker.check(m).map { it.code }.sorted() shouldBe listOf(501, 502, 503)
        }

        test("refPart is a Reference edge with an open arrow and no diamond; role name is the label, multiplicity the end label") {
            val m =
                sysml2Model(name = "M") {
                    val pump = partDef("Pump")
                    val tank = partDef("Tank")
                    val hub =
                        partDef("Hub") {
                            refPart("pumps", pump.id, dev.kuml.kerml.KermlMultiplicity.ONE_OR_MORE)
                            part("tank", tank.id)
                        }
                    bdd("B") {
                        include(hub)
                        include(pump)
                        include(tank)
                    }
                }
            val d = m.diagrams.filterIsInstance<BdDiagram>().single()
            val edges = sysml2BddProjection(model = m, diagram = d).edges.associateBy { it.id }
            edges.getValue("agg:Hub::pumps").kind shouldBe BddEdgeKind.Reference
            edges.getValue("agg:Hub::pumps").label shouldBe "pumps"
            edges.getValue("agg:Hub::pumps").endLabel shouldBe "1..*"
            edges.getValue("comp:Hub::tank").endLabel shouldBe null
            val adapter = BddEdgeAdapter(model = m, diagram = d)
            adapter.metadataFor("agg:Hub::pumps")!!.arrowHead shouldBe Sysml2ArrowHead.OpenAngle
            adapter.metadataFor("agg:Hub::pumps")!!.headAtSource shouldBe false
            adapter.metadataFor("comp:Hub::tank")!!.arrowHead shouldBe Sysml2ArrowHead.FilledDiamond
            adapter.metadataFor("comp:Hub::tank")!!.label shouldBe "tank"
        }
    })
