package dev.kuml.web.render

import dev.kuml.erm.model.ErmNotation
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexExportSupport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

/** Nacharbeit 128 - the web playground produces LaTeX for every family the CLI does and refuses the rest with the CLI's text. */
class WebRenderPipelineLatexFamiliesTest :
    FunSpec({
        beforeSpec { EngineRegistration.ensure() }

        fun resource(name: String): String =
            checkNotNull(WebRenderPipelineLatexFamiliesTest::class.java.getResource("/latex/$name.kuml.kts")) { "missing $name" }.readText()

        fun latex(
            script: String,
            standalone: Boolean = false,
            notation: String? = null,
        ): WebRenderResult =
            WebRenderPipeline.render(
                script = script,
                format = "latex",
                themeName = null,
                layoutOverride = null,
                standaloneTex = standalone,
                notation = notation,
            )

        val c4Script =
            """
            c4Model(name = "TestSystem") {
                val user = person(name = "User")
                val sys = softwareSystem(name = "System") {
                    container(name = "Web") { technology = "Ktor" }
                }
                relationship(source = user, target = sys) { technology = "HTTPS" }
                containerDiagram(name = "Test Container View") {
                    system = sys
                    showExternalSystems = false
                }
            }
            """.trimIndent()

        val packageScript =
            """
            import dev.kuml.sysml2.dsl.sysml2Model
            sysml2Model(name = "M") {
                pkg("A") { partDef("X", id = "A::X") }
                pkg("B") { imports("A::*"); partDef("Y", id = "B::Y") }
                packageDiagram("D") { includeById("pkg:A"); includeById("pkg:B") }
            }
            """.trimIndent()

        val conversationScript =
            """
            import dev.kuml.bpmn.dsl.*
            import dev.kuml.bpmn.model.*
            bpmnModel(name = "M") {
                conversation(id = "conv1") {
                    val a = participant("A")
                    val b = participant("B")
                    node(name = "N", a, b)
                }
                conversationDiagram(name = "Overview", conversationId = "conv1")
            }
            """.trimIndent()

        val bddScript =
            """
            import dev.kuml.sysml2.dsl.sysml2Model
            sysml2Model("TestModel") {
                val vehicle = partDef("Vehicle")
                bdd("Test BDD") { include(vehicle) }
            }
            """.trimIndent()

        val umlScript =
            """
            classDiagram(name = "Test") {
                val a = classOf(name = "Alpha") { attribute(name = "x", type = "Int") }
                val b = classOf(name = "Beta")
                association(source = a, target = b)
            }
            """.trimIndent()

        context("every supported family renders TikZ") {
            mapOf(
                "UML class" to umlScript,
                "C4" to c4Script,
                "SysML2 BDD" to bddScript,
                "BPMN process" to resource("bpmn-process"),
                "Blueprint" to resource("blueprint"),
                "ERM Martin" to resource("erm-martin"),
                "ERM Bachman" to resource("erm-bachman"),
                "ERM IDEF1X" to resource("erm-idef1x"),
            ).forEach { (family, script) ->
                test(family) {
                    val snippet = latex(script = script).shouldBeInstanceOf<WebRenderResult.Latex>().tex
                    snippet shouldContain "\\begin{tikzpicture}"
                    snippet shouldNotContain "\\documentclass"
                    val standalone = latex(script = script, standalone = true).shouldBeInstanceOf<WebRenderResult.Latex>().tex
                    standalone shouldContain "\\documentclass"
                    standalone shouldContain "\\begin{tikzpicture}"
                }
            }
        }

        context("unsupported families are refused with the shared text") {
            test("ERM Chen via the notation override") {
                val err = latex(script = resource("erm-martin"), notation = "chen").shouldBeInstanceOf<WebRenderResult.Error>().message
                err shouldBe KumlLatexRenderer.ermUnsupportedNotationMessage(ErmNotation.CHEN)
            }
            test("SysML2 package diagram") {
                val err = latex(script = packageScript).shouldBeInstanceOf<WebRenderResult.Error>().message
                err shouldBe KumlLatexRenderer.PACKAGE_DIAGRAM_UNSUPPORTED
            }
            test("BPMN choreography") {
                val err = latex(script = resource("bpmn-choreography")).shouldBeInstanceOf<WebRenderResult.Error>().message
                err shouldBe LatexExportSupport.BPMN_CHOREOGRAPHY_UNSUPPORTED
            }
            test("BPMN conversation") {
                val err = latex(script = conversationScript).shouldBeInstanceOf<WebRenderResult.Error>().message
                err shouldBe LatexExportSupport.BPMN_CONVERSATION_UNSUPPORTED
            }
        }

        context("ERM notation override") {
            test("a Martin script with notation=chen is refused") {
                latex(script = resource("erm-martin"), notation = "chen").shouldBeInstanceOf<WebRenderResult.Error>()
            }
            test("a script with notation=martin is rendered") {
                latex(script = resource("erm-idef1x"), notation = "martin")
                    .shouldBeInstanceOf<WebRenderResult.Latex>()
                    .tex shouldContain "\\begin{tikzpicture}"
            }
            test("an unknown notation keeps the existing message") {
                val err = latex(script = resource("erm-martin"), notation = "uml").shouldBeInstanceOf<WebRenderResult.Error>().message
                err shouldContain "Unknown ERM notation"
            }
        }

        context("the SVG result tells whether LaTeX is available") {
            fun svg(
                script: String,
                notation: String? = null,
            ): WebRenderResult.Svg =
                WebRenderPipeline
                    .render(script = script, format = "svg", themeName = null, layoutOverride = null, notation = notation)
                    .shouldBeInstanceOf<WebRenderResult.Svg>()

            test("UML, BPMN process and ERM Martin are supported") {
                listOf(umlScript, resource("bpmn-process"), resource("erm-martin"), resource("blueprint"), c4Script).forEach {
                    val r = svg(it)
                    r.latexSupported shouldBe true
                    r.latexUnsupportedReason shouldBe null
                }
            }
            test("ERM Chen via override, package diagram, choreography and conversation carry the reason") {
                svg(resource("erm-martin"), notation = "chen").let {
                    it.latexSupported shouldBe false
                    it.latexUnsupportedReason shouldBe KumlLatexRenderer.ermUnsupportedNotationMessage(ErmNotation.CHEN)
                }
                svg(packageScript).latexUnsupportedReason shouldBe KumlLatexRenderer.PACKAGE_DIAGRAM_UNSUPPORTED
                svg(resource("bpmn-choreography")).latexUnsupportedReason shouldBe LatexExportSupport.BPMN_CHOREOGRAPHY_UNSUPPORTED
                svg(conversationScript).latexUnsupportedReason shouldBe LatexExportSupport.BPMN_CONVERSATION_UNSUPPORTED
            }
            test("PNG and LaTeX results carry no such fields; errors are errors") {
                WebRenderPipeline
                    .render(script = umlScript, format = "png", themeName = null, layoutOverride = null)
                    .shouldBeInstanceOf<WebRenderResult.Png>()
                latex(script = "not a script @@@").shouldBeInstanceOf<WebRenderResult.Error>()
            }
        }

        context("no new limit") {
            test("a large diagram still yields a complete LaTeX document") {
                val classes =
                    (1..60).joinToString(
                        "\n",
                    ) { "    classOf(name = \"Class$it\") { attribute(name = \"v$it\", type = \"Int\") }" }
                val tex = latex(script = "classDiagram(name = \"Big\") {\n$classes\n}").shouldBeInstanceOf<WebRenderResult.Latex>().tex
                (tex.length > 5_000) shouldBe true
                tex shouldContain "Class60"
            }
        }
    })
