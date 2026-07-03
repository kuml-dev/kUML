package dev.kuml.web.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

class WebRenderPipelineLatexTest :
    FunSpec({

        beforeSpec {
            EngineRegistration.ensure()
        }

        val umlClassScript =
            """
            classDiagram(name = "Test") {
                val a = classOf(name = "Alpha") {
                    attribute(name = "x", type = "Int")
                }
                val b = classOf(name = "Beta") {
                    attribute(name = "y", type = "String")
                }
                association(source = a, target = b)
            }
            """.trimIndent()

        val sysml2BddScript =
            """
            import dev.kuml.sysml2.dsl.sysml2Model
            sysml2Model("TestModel") {
                val massType = attributeDef("Mass")
                val vehicle = partDef("Vehicle") {
                    attribute("weight", typeId = massType.id)
                }
                bdd("Test BDD") {
                    include(vehicle)
                }
            }
            """.trimIndent()

        val invalidScript = "this is not valid kUML @@@ !!!"

        test("latex format returns TikZ snippet for UML class diagram") {
            val result = WebRenderPipeline.render(umlClassScript, "latex", null, null)
            val tex = result.shouldBeInstanceOf<WebRenderResult.Latex>().tex
            tex.shouldNotBeEmpty()
            tex shouldContain "tikzpicture"
        }

        test("latex format returns TikZ for SysML2 BDD diagram") {
            val result = WebRenderPipeline.render(sysml2BddScript, "latex", null, null)
            val tex = result.shouldBeInstanceOf<WebRenderResult.Latex>().tex
            tex.shouldNotBeEmpty()
            tex shouldContain "tikzpicture"
        }

        test("latex format with standaloneTex=true contains documentclass") {
            val result = WebRenderPipeline.render(umlClassScript, "latex", null, null, standaloneTex = true)
            val tex = result.shouldBeInstanceOf<WebRenderResult.Latex>().tex
            tex shouldContain "\\documentclass"
            tex shouldContain "\\begin{document}"
            tex shouldContain "\\end{document}"
        }

        test("latex format snippet does NOT contain documentclass when standaloneTex=false") {
            val result = WebRenderPipeline.render(umlClassScript, "latex", null, null, standaloneTex = false)
            val tex = result.shouldBeInstanceOf<WebRenderResult.Latex>().tex
            tex shouldNotContain "\\documentclass"
            tex shouldContain "tikzpicture"
        }

        test("unknown format returns WebRenderResult.Error") {
            val result = WebRenderPipeline.render(umlClassScript, "xml", null, null)
            val err = result.shouldBeInstanceOf<WebRenderResult.Error>().message
            err shouldContain "xml"
        }
    })
