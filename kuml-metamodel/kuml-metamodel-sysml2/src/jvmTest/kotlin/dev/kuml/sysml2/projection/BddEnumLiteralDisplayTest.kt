package dev.kuml.sysml2.projection

import dev.kuml.kerml.KermlMultiplicity
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

/**
 * Nacharbeit 72 (b), (g1), (g2): compact enumeration literals, derived marker and external block stereotype.
 * String level, font independent.
 */
class BddEnumLiteralDisplayTest :
    FunSpec(body = {
        fun model(includeEnum: Boolean): Sysml2Model =
            sysml2Model(name = "N") {
                val unit1 = attributeDef(name = "Unit1", unitSymbol = "V")
                val mode =
                    enumerationDef(name = "Mode") {
                        for (c in 'A'..'C') literal(name = c.toString())
                    }
                val big =
                    enumerationDef(name = "Big") {
                        for (c in 'A'..'G') literal(name = c.toString())
                    }
                val empty = enumerationDef(name = "Empty")
                val block =
                    partDef(name = "Block1", external = true) {
                        attribute(name = "m", typeId = mode.id)
                        attribute(
                            name = "n",
                            typeId = mode.id,
                            multiplicity = KermlMultiplicity.ZERO_OR_MORE,
                            defaultLiteral = "A",
                        )
                        attribute(name = "b", typeId = big.id)
                        attribute(name = "e", typeId = empty.id)
                        attribute(name = "d", typeId = unit1.id, derived = true)
                        attribute(name = "p", typeId = unit1.id)
                    }
                bdd(name = "D") {
                    include(block)
                    include(unit1)
                    if (includeEnum) include(mode)
                }
            }

        fun lines(
            m: Sysml2Model,
            withProjection: Boolean = true,
        ): List<String> {
            val diagram = m.diagrams.single() as BdDiagram
            val proj = if (withProjection) sysml2BddProjection(model = m, diagram = diagram) else null
            return sysml2BddBoxContent(model = m, def = m.definitions.first { it.id == "Block1" }, projection = proj).featureLines
        }

        test("not drawn enum shows literals, long lists end with +N, empty enum and unit type show nothing extra") {
            lines(m = model(includeEnum = false)) shouldContainExactly
                listOf(
                    "m : Mode {A | B | C}",
                    "n : Mode {A | B | C} [0..*] = A",
                    "b : Big {A | B | C | D | +3}",
                    "e : Empty",
                    "/d : Unit1",
                    "p : Unit1",
                )
        }

        test("an included enum gets no braces") {
            lines(m = model(includeEnum = true)).first() shouldBe "m : Mode"
        }

        test("no projection gives no braces") {
            lines(m = model(includeEnum = false), withProjection = false).first() shouldBe "m : Mode"
        }

        test("compactLiteralSuffix cases") {
            compactLiteralSuffix(literals = listOf("a", "b", "c", "d")) shouldBe " {a | b | c | d}"
            compactLiteralSuffix(literals = listOf("a", "b", "c", "d", "e")) shouldBe " {a | b | c | d | +1}"
            compactLiteralSuffix(literals = emptyList()) shouldBe ""
            compactLiteralSuffix(literals = listOf("x".repeat(40))) shouldBe " {${"x".repeat(BDD_LITERAL_CHAR_CAP)}…}"
        }

        test("bddStereotype marks external part defs only") {
            val m = model(includeEnum = false)
            bddStereotype(def = m.definitions.first { it.id == "Block1" }) shouldBe "part def, external"
            bddStereotype(def = m.definitions.first { it.id == "Mode" }) shouldBe "enumeration"
            val plain = sysml2Model(name = "P") { partDef(name = "X") }
            bddStereotype(def = plain.definitions.single()) shouldBe "part def"
        }

        test("printer emits derived and external, plain models are unchanged") {
            val printed = Sysml2DslPrinter.print(model = model(includeEnum = false))
            printed shouldContain "derived = true"
            printed shouldContain "external = true"
            val plain =
                Sysml2DslPrinter.print(
                    model =
                        sysml2Model(name = "P") {
                            val u = attributeDef(name = "U")
                            partDef(name = "X") { attribute(name = "a", typeId = u.id) }
                        },
                )
            plain shouldNotContain "derived"
            plain shouldNotContain "external"
        }

        test("JSON round trip keeps both flags") {
            val m = model(includeEnum = false)
            val json = Json.encodeToString(Sysml2Model.serializer(), m)
            json shouldContain DERIVED_META_KEY
            json shouldContain EXTERNAL_META_KEY
            Json.decodeFromString(Sysml2Model.serializer(), json) shouldBe m
        }
    })
