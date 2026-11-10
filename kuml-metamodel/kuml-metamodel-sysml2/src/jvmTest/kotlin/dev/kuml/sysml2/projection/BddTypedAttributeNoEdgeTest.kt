package dev.kuml.sysml2.projection

import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Nacharbeit 53 (c) and (d). (c): an attribute typed by an included value or enumeration definition gets no edge; the
 * type stays in the attribute line. (d): the default text of the line (string level, font independent).
 */
class BddTypedAttributeNoEdgeTest :
    FunSpec(body = {
        val model =
            sysml2Model(name = "N") {
                val pressure = attributeDef(name = "Pressure", unitSymbol = "Pa")
                val mode = enumerationDef(name = "Mode") { literal(name = "On") }
                val controller =
                    partDef(name = "Controller") {
                        attribute(name = "a1", typeId = pressure.id, default = -5.0)
                        attribute(name = "a2", typeId = pressure.id, defaultLiteral = "-5")
                        attribute(name = "a3", typeId = pressure.id)
                        attribute(name = "mode", typeId = mode.id, defaultLiteral = "On")
                    }
                bdd(name = "D") {
                    include(controller)
                    include(pressure)
                    include(mode)
                }
            }
        val diagram = model.diagrams.single() as BdDiagram

        test("(c) no edge points at an included attribute or enumeration definition") {
            val proj = sysml2BddProjection(model = model, diagram = diagram)
            proj.edges.map { it.targetId } shouldNotContain "Pressure"
            proj.edges.map { it.targetId } shouldNotContain "Mode"
            proj.edges.map { it.sourceId } shouldNotContain "Controller"
        }

        test("(c) the typed attribute usages stay feature lines") {
            val proj = sysml2BddProjection(model = model, diagram = diagram)
            proj.hiddenFeatureIds shouldNotContain "Controller::a1"
            proj.hiddenFeatureIds shouldNotContain "Controller::mode"
        }

        test("(d) a numeric default shows the unit of the type, a defaultLiteral stays verbatim, no default shows no unit") {
            val proj = sysml2BddProjection(model = model, diagram = diagram)
            val controller = model.definitions.first { it.id == "Controller" }
            sysml2BddBoxContent(model = model, def = controller, projection = proj).featureLines shouldContainExactly
                listOf(
                    "a1 : Pressure = -5 Pa",
                    "a2 : Pressure = -5",
                    "a3 : Pressure",
                    "mode : Mode = On",
                )
        }

        test("(d) an attribute definition with a custom id is not found by its name, so the unit is dropped") {
            val m =
                sysml2Model(name = "N") {
                    attributeDef(name = "Temperature", unitSymbol = "K", id = "tmp")
                    partDef(name = "Controller") {
                        attribute(name = "t", typeId = "Temperature", default = 3.0)
                        attribute(name = "u", typeId = "tmp", default = 3.0)
                    }
                }
            val lines =
                sysml2BddBoxContent(model = m, def = m.definitions.first { it.id == "Controller" }, projection = null).featureLines
            lines shouldContainExactly listOf("t : Temperature = 3", "u : tmp = 3 K")
        }

        test("(d) formatAttributeValue pins the number forms") {
            formatAttributeValue(raw = "-5.0", typeUnit = "Pa") shouldBe "-5 Pa"
            formatAttributeValue(raw = "5.0[Pa]", typeUnit = null) shouldBe "5 Pa"
            formatAttributeValue(raw = "-5", typeUnit = "Pa", isLiteral = true) shouldBe "-5"
        }
    })
