package dev.kuml.vaultexamples

import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.sysml2.AttributeUsage
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.script.experimental.api.ResultWithDiagnostics

/**
 * Nacharbeit 109: the DSL printer keeps attribute defaults. Each case evaluates a script, prints
 * the model, evaluates the printed text again and asserts that `defaultExpression` and the metadata
 * flags survive, then renders a diagram and checks the default text (font independent, no pixels).
 */
class Sysml2AttributeDefaultRoundTripTest :
    FunSpec({
        fun evalModel(code: String): Sysml2Model {
            VaultExampleRenderer.init()
            val res = KumlScriptHost.eval(code = code)
            check(res is ResultWithDiagnostics.Success) { res.reports.joinToString("\n") { it.message } }
            val ok = res
            val ex = DiagramExtractor.extractAny(returnValue = ok.value.returnValue, input = java.io.File("inline.kuml.kts"))
            return (ex as ExtractedDiagram.Sysml2).model
        }

        fun script(attributeLine: String): String =
            """
            sysml2Model("RoundTrip") {
                attributeDef(name = "T")
                enumerationDef(name = "Colour") { literal("Red") }
                val vehicle =
                    partDef(name = "Vehicle") {
                        $attributeLine
                    }
                bdd(name = "Main") { include(definition = vehicle) }
            }
            """.trimIndent()

        fun usage(model: Sysml2Model): AttributeUsage = model.usages.filterIsInstance<AttributeUsage>().single()

        fun check(
            attributeLine: String,
            expectedExpression: String,
            expectedInSvg: String,
        ) {
            val original = evalModel(script(attributeLine))
            usage(original).defaultExpression shouldBe expectedExpression
            val printed = Sysml2DslPrinter.print(original)
            val reparsed = evalModel(printed)
            usage(reparsed).defaultExpression shouldBe usage(original).defaultExpression
            usage(reparsed).metadata shouldBe usage(original).metadata
            usage(reparsed).multiplicity shouldBe usage(original).multiplicity
            val r = VaultExampleRenderer.render(script = printed)
            r.error shouldBe null
            r.svg!! shouldContain expectedInSvg
        }

        test("negative double default") {
            check("""attribute(name = "x", typeId = "T", default = -5.0)""", "-5.0", "= -5")
        }

        test("unit default 5.Pa") {
            check(
                """attribute(name = "x", typeId = "T", default = dev.kuml.sysml2.units.UnitValue(5.0, dev.kuml.sysml2.units.Unit.PASCAL))""",
                "5.0[Pa]",
                "5.0",
            )
        }

        test("literal 1.0 keeps the literal flag") {
            check("""attribute(name = "x", typeId = "T", defaultLiteral = "1.0")""", "1.0", "1.0")
        }

        test("enum literal Red") {
            check("""attribute(name = "x", typeId = "Colour", defaultLiteral = "Red")""", "Red", "Red")
        }

        test("unknown unit symbol with derived and multiplicity") {
            check(
                """attribute(name = "x", typeId = "T", multiplicity = dev.kuml.kerml.KermlMultiplicity.OPTIONAL, default = dev.kuml.sysml2.units.UnitValue(2.5, dev.kuml.sysml2.units.Unit("zz", "zzz", "Q")), derived = true)""",
                "2.5[zz]",
                "2.5",
            )
        }
    })
