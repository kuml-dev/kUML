package dev.kuml.vaultexamples

import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics

/**
 * SysML 2 STM wave A1 (composite states, regions, pseudo-states, history) — full pipeline
 * (script, ELK layout, SVG, PNG). Scripts are test resources, assertions are font independent
 * (skeleton counts, ids, CSS classes); the PNGs go to build/sample-output for visual inspection.
 */
class Sysml2StmA1RenderTest :
    FunSpec({
        fun script(name: String): String =
            Sysml2StmA1RenderTest::class.java
                .getResourceAsStream("/sysml2-stm-a1/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        fun count(
            s: String,
            needle: String,
        ) = Regex(Regex.escape(needle)).findAll(s).count()

        test("composite state with history") {
            val r = VaultExampleRenderer.render(script = script("composite-history"))
            r.error shouldBe null
            val svg = r.svg!!
            SampleOutput.write(filename = "sysml2-stm-a1/composite-history.svg", content = svg)
            count(svg, "class=\"kuml-composite-state\"") shouldBe 1
            svg shouldContain "id=\"On\""
            svg shouldContain "id=\"H\""
            Regex(">\\s*H\\s*<").containsMatchIn(svg) shouldBe true
            svg shouldContain "entry / powerUp()"
            // composite anchors never leak as elements
            svg shouldNotContain "stm:anchor"
            svg shouldNotContain "stm:order"
        }

        test("orthogonal regions with fork and join") {
            val r = VaultExampleRenderer.render(script = script("orthogonal-fork-join"))
            r.error shouldBe null
            val svg = r.svg!!
            SampleOutput.write(filename = "sysml2-stm-a1/orthogonal-fork-join.svg", content = svg)
            count(svg, "class=\"kuml-divider\" stroke-dasharray=\"6 4\"") shouldBe 1
            count(svg, "class=\"kuml-fork-bar\"") shouldBe 2
            svg shouldContain "font-style=\"italic\""
            Regex(">\\s*Left\\s*<").containsMatchIn(svg) shouldBe true
            Regex(">\\s*Right\\s*<").containsMatchIn(svg) shouldBe true
            svg shouldNotContain "stm:order"
        }

        test("choice and junction") {
            val r = VaultExampleRenderer.render(script = script("choice-junction"))
            r.error shouldBe null
            val svg = r.svg!!
            SampleOutput.write(filename = "sysml2-stm-a1/choice-junction.svg", content = svg)
            count(svg, "<polygon points=\"14,0") shouldBe 1
            count(svg, "class=\"kuml-pseudostate\"") shouldBe 1
            svg shouldContain "[amount &gt; 1000]"
        }

        test("printer output replays to the same model for every example") {
            for (name in listOf("composite-history", "orthogonal-fork-join", "choice-junction")) {
                fun model(code: String): Sysml2Model {
                    val eval = KumlScriptHost.eval(code = code)
                    val ok = eval as ResultWithDiagnostics.Success
                    return (
                        DiagramExtractor.extractAny(returnValue = ok.value.returnValue, input = File("inline.kuml.kts"))
                            as ExtractedDiagram.Sysml2
                    ).model
                }
                val original = model(script(name))
                val replayed = model(Sysml2DslPrinter.print(original))
                replayed.definitions.sortedBy { it.id } shouldBe original.definitions.sortedBy { it.id }
                replayed.usages.sortedBy { it.id } shouldBe original.usages.sortedBy { it.id }
            }
        }
    })
