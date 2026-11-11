package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.AttributeUsage
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.projection.DEFAULT_LITERAL_META_KEY
import dev.kuml.sysml2.projection.NBSP
import dev.kuml.sysml2.projection.ibdPartAttributes
import dev.kuml.sysml2.projection.sysml2BddBoxContent
import dev.kuml.sysml2.projection.sysml2BddProjection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

private const val LONG_LITERAL = "nicht in diesem Abschnitt, siehe die ausfuehrliche Beschreibung weiter unten"

private fun valueModel(
    literal: String? = LONG_LITERAL,
    withNumbers: Boolean = true,
): Sysml2Model =
    sysml2Model(name = "ValueWidth") {
        val num = attributeDef(name = "Zahl")
        val state = enumerationDef(name = "Zustand") { literal("a") }
        partDef(name = "Kennwert") {
            if (literal != null) attribute(name = "hinweis", typeId = state.id, defaultLiteral = literal)
            if (withNumbers) {
                attribute(name = "textEins", typeId = num.id, defaultLiteral = "1.0")
                attribute(name = "zahlEins", typeId = num.id, default = 1.0)
            }
        }
        bdd(name = "BDD") { includeById(id = "Kennwert") }
    }

private fun widthOf(model: Sysml2Model): Float {
    val bdd = model.diagrams.filterIsInstance<BdDiagram>().single()
    return Sysml2LayoutBridge
        .bddContentAwareSizeProvider(model = model, diagram = bdd)
        .sizeOf(elementId = "Kennwert", elementKind = "PartDefinition")
        .width
}

class Sysml2BddValueWidthTest :
    FunSpec({
        test("every feature line (value included) fits the box width of the size provider") {
            val model = valueModel()
            val bdd = model.diagrams.filterIsInstance<BdDiagram>().single()
            val def = model.definitions.single { it.id == "Kennwert" }
            val content = sysml2BddBoxContent(model = model, def = def, projection = null)
            val width = widthOf(model)
            content.featureLines.size shouldBe 3
            for (line in content.featureLines) {
                (line.length * Sysml2LayoutBridge.BDD_BODY_CHAR_PX + 2 * Sysml2LayoutBridge.IBD_H_PAD) shouldBeLessThanOrEqualTo width
            }
            bdd.elementIds shouldBe listOf("Kennwert")
        }

        test("a box with a long default is wider than the same box without a default") {
            widthOf(valueModel(literal = LONG_LITERAL, withNumbers = false)) shouldBeGreaterThan
                widthOf(valueModel(literal = null, withNumbers = false))
        }

        test("defaultLiteral 1.0 stays verbatim, a numeric default 1.0 is normalised to 1") {
            val model = valueModel()
            val def = model.definitions.single { it.id == "Kennwert" }
            val lines = sysml2BddBoxContent(model = model, def = def, projection = null).featureLines
            lines shouldContain "textEins : Zahl = 1.0"
            lines shouldContain "zahlEins : Zahl = 1"
        }

        test("IBD: numeric default gets unit with NBSP, a literal stays verbatim") {
            val pressure = AttributeDefinition(id = "Pressure", name = "Pressure", unit = "Pa")
            val part = PartDefinition(id = "P", name = "P")
            val usage = PartUsage(id = "p", name = "p", definitionId = "P")
            val numeric =
                AttributeUsage(id = "P::a", name = "a", definitionId = "Pressure", defaultExpression = "-5.0")
            val literal =
                AttributeUsage(
                    id = "P::b",
                    name = "b",
                    definitionId = "Pressure",
                    defaultExpression = "-5.0",
                    metadata = mapOf(DEFAULT_LITERAL_META_KEY to KumlMetaValue.Flag(value = true)),
                )
            val model = Sysml2Model(name = "M", definitions = listOf(pressure, part), usages = listOf(usage, numeric, literal))
            val lines = ibdPartAttributes(model = model, usage = usage).lines
            lines.single { it.name == "a" }.value shouldBe "-5${NBSP}Pa"
            lines.single { it.name == "b" }.value shouldBe "-5.0"
        }

        test("JSON round trip keeps the literal flag; models without defaultLiteral do not carry it") {
            val withLiteral = valueModel()
            val json = Json.encodeToString(Sysml2Model.serializer(), withLiteral)
            val back = Json.decodeFromString(Sysml2Model.serializer(), json)
            back shouldBe withLiteral
            json shouldNotBe ""
            val plain = valueModel(literal = null, withNumbers = false)
            val plainJson = Json.encodeToString(Sysml2Model.serializer(), plain)
            plainJson shouldNotContain DEFAULT_LITERAL_META_KEY
            val numericOnly =
                sysml2Model(name = "N") {
                    val num = attributeDef(name = "Zahl")
                    partDef(name = "K") { attribute(name = "z", typeId = num.id, default = 2.0) }
                }
            Json.encodeToString(Sysml2Model.serializer(), numericOnly) shouldNotContain DEFAULT_LITERAL_META_KEY
        }

        test("Nacharbeit 72: a literal suffix and the external stereotype widen the box") {
            val model =
                sysml2Model(name = "L") {
                    val mode = enumerationDef(name = "Mode") { literal("LongLiteralOne") }
                    partDef(name = "Kennwert", external = true) { attribute(name = "m", typeId = mode.id) }
                    bdd(name = "BDD") { includeById(id = "Kennwert") }
                }
            val bdd = model.diagrams.filterIsInstance<BdDiagram>().single()
            val def = model.definitions.single { it.id == "Kennwert" }
            val content =
                sysml2BddBoxContent(model = model, def = def, projection = sysml2BddProjection(model = model, diagram = bdd))
            content.featureLines shouldBe listOf("m : Mode {LongLiteralOne}")
            val width = widthOf(model)
            val lineW = content.featureLines.maxOf { it.length } * Sysml2LayoutBridge.BDD_BODY_CHAR_PX
            val stereoW = "«part def, external»".length * Sysml2LayoutBridge.IBD_STEREO_CHAR_PX
            width shouldBeGreaterThanOrEqualTo (lineW + 2 * Sysml2LayoutBridge.IBD_H_PAD)
            width shouldBeGreaterThanOrEqualTo (stereoW + 2 * Sysml2LayoutBridge.IBD_H_PAD)
        }
    })
