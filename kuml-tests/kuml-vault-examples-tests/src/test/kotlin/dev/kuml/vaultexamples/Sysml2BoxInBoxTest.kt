package dev.kuml.vaultexamples

import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.projection.Sysml2PathResolver
import dev.kuml.sysml2.projection.sysml2IbdProjection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics

/**
 * PAL wave 2C-b2 fixture "IBD Box-in-Box 3 Ebenen": line style and colour of a `connectionDef`, the attribute
 * compartment of a part, wrapped port labels, and the nested part paths of the projection.
 * The fixture lives outside `vault-examples/` because the vault sync mirrors that folder with `rsync --delete`.
 */
class Sysml2BoxInBoxTest :
    FunSpec({
        val md =
            Sysml2BoxInBoxTest::class.java
                .getResourceAsStream("/ibd-fixtures/IBD Box-in-Box 3 Ebenen.md")!!
                .bufferedReader(Charsets.UTF_8)
                .readText()
        val script = Regex("```kuml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL).find(md)!!.groupValues[1]

        fun evalModel(code: String): dev.kuml.sysml2.Sysml2Model {
            VaultExampleRenderer.init()
            val ok = KumlScriptHost.eval(code = code) as ResultWithDiagnostics.Success
            val extracted = DiagramExtractor.extractAny(returnValue = ok.value.returnValue, input = File("inline.kuml.kts"))
            return (extracted as ExtractedDiagram.Sysml2).model
        }

        test("the fixture renders, with dashed coloured connection, attribute compartment and no raw entities") {
            val result = VaultExampleRenderer.render(script = script, themeName = "plain")
            (result.error == null) shouldBe true
            val svg = result.svg!!
            SampleOutput.write(filename = "vault-examples/kuml/ibd-box-in-box-3-ebenen.svg", content = svg)
            // D: the Feed connection is DASHED "6 4" in its own colour; the Signal style is not used here.
            svg shouldContain "stroke-dasharray=\"6 4\""
            svg shouldContain "style=\"stroke:#1D2968\""
            // C: the attribute with a value shows `name : Type = value`, the one without shows no `=`.
            svg shouldContain "weight : Mass = 12"
            svg shouldContain "serial : Mass"
            svg shouldNotContain "serial : Mass ="
            svg shouldContain "kuml-ibd-attribute"
            svg shouldNotContain "&amp;apos;"
            svg shouldNotContain "&amp;lt;"
        }

        test("without the attribute compartment (showAttributes = false) the attribute lines disappear") {
            val off = script.replace("ibd(name = \"Plant\", owner = plant)", "ibd(name = \"Plant\", owner = plant, showAttributes = false)")
            val svg = VaultExampleRenderer.render(script = off, themeName = "plain").svg!!
            svg shouldNotContain "weight : Mass"
        }

        test("the projection finds the three nested levels and resolves the deep end to the innermost part") {
            val model = evalModel(script)
            val p = sysml2IbdProjection(model = model, diagram = model.diagrams.filterIsInstance<IbdDiagram>().single())
            p.nestedPartPaths shouldContain "Plant::line::cell"
            p.nestedPartPaths shouldContain "Plant::line::cell::drive"
            val end =
                Sysml2PathResolver.resolve(
                    model = model,
                    ownerId = "Plant",
                    endpointId = "Plant::line::cell::drive::power",
                    visibleNodeIds = p.partNodes.map { it.id }.toSet(),
                    nestedNodeIds = p.nestedPartPaths.toSet(),
                )!!
            end.nodeId shouldBe "Plant::line::cell::drive"
            end.terminalPort?.name shouldBe "power"
        }
    })
