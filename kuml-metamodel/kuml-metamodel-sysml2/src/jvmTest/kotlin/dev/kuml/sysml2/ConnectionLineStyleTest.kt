package dev.kuml.sysml2

import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

/** PAL wave 2C-b2: line style and colour of a `connectionDef`, and the `showAttributes` switch of an IBD. */
class ConnectionLineStyleTest :
    FunSpec(body = {
        fun model() =
            sysml2Model(name = "M") {
                connectionDef(name = "Plain")
                connectionDef(name = "Dashed", style = ConnectionLineStyle.DASHED, color = "#aa00FF")
                connectionDef(name = "Dotted", style = ConnectionLineStyle.DOTTED)
                connectionDef(name = "DashDot", style = ConnectionLineStyle.DASH_DOT)
            }

        test("builder keeps style and colour; the defaults are SOLID and no colour") {
            val defs = model().definitions.filterIsInstance<ConnectionDefinition>().associateBy { it.name }
            defs.getValue("Plain").style shouldBe ConnectionLineStyle.SOLID
            defs.getValue("Plain").color shouldBe null
            defs.getValue("Dashed").style shouldBe ConnectionLineStyle.DASHED
            defs.getValue("Dashed").color shouldBe "#aa00FF"
            defs.getValue("DashDot").style shouldBe ConnectionLineStyle.DASH_DOT
        }

        test("JSON roundtrip keeps style and colour") {
            val m = model()
            Json.decodeFromString(Sysml2Model.serializer(), Json.encodeToString(Sysml2Model.serializer(), m)) shouldBe m
        }

        test("old JSON without the new fields still decodes to the defaults") {
            val json = Json { ignoreUnknownKeys = true }
            val def = json.decodeFromString(ConnectionDefinition.serializer(), """{"id":"A","name":"A"}""")
            def.style shouldBe ConnectionLineStyle.SOLID
            def.color shouldBe null
        }

        test("printer writes style and colour only when they differ from the default") {
            val dsl = Sysml2DslPrinter.print(model())
            dsl shouldContain "style = dev.kuml.sysml2.ConnectionLineStyle.DASHED"
            dsl shouldContain "color = \"#aa00FF\""
            dsl shouldContain "style = dev.kuml.sysml2.ConnectionLineStyle.DOTTED"
            val plainLine = dsl.lines().first { it.contains("connectionDef") && it.contains("\"Plain\"") }
            plainLine shouldNotContain "style"
            plainLine shouldNotContain "color"
        }

        test("an invalid colour is rejected, so it can never reach an SVG attribute") {
            for (bad in listOf("red", "#fff", "#12345g", "#1234567", "\"/><script>", "#aabbcc\" onload=\"x")) {
                shouldThrow<IllegalArgumentException> {
                    sysml2Model(name = "M") { connectionDef(name = "X", color = bad) }
                }
            }
        }

        test("an IBD prints showAttributes = false only when it is switched off") {
            val off =
                sysml2Model(name = "M") {
                    val p = partDef(name = "P")
                    ibd(name = "d", owner = p, showAttributes = false)
                }
            val on =
                sysml2Model(name = "M") {
                    val p = partDef(name = "P")
                    ibd(name = "d", owner = p)
                }
            Sysml2DslPrinter.print(off) shouldContain "showAttributes = false"
            Sysml2DslPrinter.print(on) shouldNotContain "showAttributes"
            (off.diagrams.single() as IbdDiagram).showAttributes shouldBe false
            (on.diagrams.single() as IbdDiagram).showAttributes shouldBe true
        }
    })
