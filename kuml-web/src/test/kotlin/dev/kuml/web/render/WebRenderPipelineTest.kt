package dev.kuml.web.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf

class WebRenderPipelineTest :
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

        val c4Script =
            """
            c4Model(name = "TestSystem") {
                val user = person(name = "User")
                val sys = softwareSystem(name = "System") {
                    container(name = "Web") {
                        technology = "Ktor"
                    }
                }
                relationship(source = user, target = sys) { technology = "HTTPS" }
                containerDiagram(name = "Test Container View") {
                    system = sys
                    showExternalSystems = false
                }
            }
            """.trimIndent()

        val sysml2Script =
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

        test("UML class script renders to SVG containing <svg tag") {
            val result = WebRenderPipeline.render(umlClassScript, "svg", null, null)
            result.shouldBeInstanceOf<WebRenderResult.Svg>()
            (result as WebRenderResult.Svg).svg shouldContain "<svg"
        }

        test("UML class script renders to PNG with correct magic bytes") {
            val result = WebRenderPipeline.render(umlClassScript, "png", null, null)
            result.shouldBeInstanceOf<WebRenderResult.Png>()
            val bytes = (result as WebRenderResult.Png).pngBytes
            // PNG magic bytes: 89 50 4E 47
            bytes[0] shouldBe 0x89.toByte()
            bytes[1] shouldBe 0x50.toByte()
            bytes[2] shouldBe 0x4E.toByte()
            bytes[3] shouldBe 0x47.toByte()
        }

        test("Invalid script returns WebRenderResult.Error") {
            val result = WebRenderPipeline.render(invalidScript, "svg", null, null)
            result.shouldBeInstanceOf<WebRenderResult.Error>()
            (result as WebRenderResult.Error).message.shouldNotBeEmpty()
        }

        test("C4 container script renders to SVG") {
            val result = WebRenderPipeline.render(c4Script, "svg", null, null)
            result.shouldBeInstanceOf<WebRenderResult.Svg>()
            (result as WebRenderResult.Svg).svg shouldContain "<svg"
        }

        test("SysML2 BDD script renders to SVG") {
            val result = WebRenderPipeline.render(sysml2Script, "svg", null, null)
            result.shouldBeInstanceOf<WebRenderResult.Svg>()
            (result as WebRenderResult.Svg).svg shouldContain "<svg"
        }
    })
