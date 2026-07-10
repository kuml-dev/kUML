package dev.kuml.web.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

        val ermScript =
            """
            ermModel("Blog") {
                val author =
                    entity("Author") {
                        id()
                        attribute(name = "name", type = ErmDataType.Varchar(120), nullable = false)
                    }
                val post =
                    entity("Post") {
                        id()
                        foreignKey(name = "author_id", references = author, nullable = false)
                        attribute(name = "title", type = ErmDataType.Varchar(255), nullable = false)
                    }
                relationship(from = author, to = post, name = "writes")
            }
            """.trimIndent()

        val invalidScript = "this is not valid kUML @@@ !!!"

        test("UML class script renders to SVG containing <svg tag") {
            val result = WebRenderPipeline.render(umlClassScript, "svg", null, null)
            result.shouldBeInstanceOf<WebRenderResult.Svg>()
            result.svg shouldContain "<svg"
        }

        test("UML class script populates node geometry and grid in the result") {
            val result = WebRenderPipeline.render(umlClassScript, "svg", null, null)
            val svgResult = result.shouldBeInstanceOf<WebRenderResult.Svg>()
            svgResult.nodes.shouldNotBeEmpty()
            svgResult.nodes.map { it.id } shouldContainAll listOf("Alpha", "Beta")
            svgResult.grid shouldNotBe null
        }

        test("C4 script does not populate a grid (feature stays UML-class-only)") {
            val result = WebRenderPipeline.render(c4Script, "svg", null, null)
            val svgResult = result.shouldBeInstanceOf<WebRenderResult.Svg>()
            svgResult.grid shouldBe null
        }

        test("SysML2 script does not populate a grid (feature stays UML-class-only)") {
            val result = WebRenderPipeline.render(sysml2Script, "svg", null, null)
            val svgResult = result.shouldBeInstanceOf<WebRenderResult.Svg>()
            svgResult.grid shouldBe null
        }

        test("UML class script renders to PNG with correct magic bytes") {
            val result = WebRenderPipeline.render(umlClassScript, "png", null, null)
            val bytes = result.shouldBeInstanceOf<WebRenderResult.Png>().pngBytes
            // PNG magic bytes: 89 50 4E 47
            bytes[0] shouldBe 0x89.toByte()
            bytes[1] shouldBe 0x50.toByte()
            bytes[2] shouldBe 0x4E.toByte()
            bytes[3] shouldBe 0x47.toByte()
        }

        test("Invalid script returns WebRenderResult.Error") {
            val result = WebRenderPipeline.render(invalidScript, "svg", null, null)
            result.shouldBeInstanceOf<WebRenderResult.Error>()
            result.message.shouldNotBeEmpty()
        }

        test("C4 container script renders to SVG") {
            val result = WebRenderPipeline.render(c4Script, "svg", null, null)
            result.shouldBeInstanceOf<WebRenderResult.Svg>()
            result.svg shouldContain "<svg"
        }

        test("SysML2 BDD script renders to SVG") {
            val result = WebRenderPipeline.render(sysml2Script, "svg", null, null)
            result.shouldBeInstanceOf<WebRenderResult.Svg>()
            result.svg shouldContain "<svg"
        }

        test("ERM script renders to SVG containing <svg tag") {
            val result = WebRenderPipeline.render(ermScript, "svg", null, null)
            result.shouldBeInstanceOf<WebRenderResult.Svg>()
            result.svg shouldContain "<svg"
        }

        test("ERM script renders to PNG with correct magic bytes") {
            val result = WebRenderPipeline.render(ermScript, "png", null, null)
            val bytes = result.shouldBeInstanceOf<WebRenderResult.Png>().pngBytes
            bytes[0] shouldBe 0x89.toByte()
            bytes[1] shouldBe 0x50.toByte()
            bytes[2] shouldBe 0x4E.toByte()
            bytes[3] shouldBe 0x47.toByte()
        }

        test("ERM notation override to CHEN renders to SVG") {
            val result = WebRenderPipeline.render(ermScript, "svg", null, null, notation = "chen")
            result.shouldBeInstanceOf<WebRenderResult.Svg>()
            result.svg shouldContain "<svg"
        }

        test("ERM notation override to BACHMAN renders to SVG") {
            val result = WebRenderPipeline.render(ermScript, "svg", null, null, notation = "bachman")
            result.shouldBeInstanceOf<WebRenderResult.Svg>()
            result.svg shouldContain "<svg"
        }

        test("ERM notation override to IDEF1X renders to SVG") {
            val result = WebRenderPipeline.render(ermScript, "svg", null, null, notation = "idef1x")
            result.shouldBeInstanceOf<WebRenderResult.Svg>()
            result.svg shouldContain "<svg"
        }

        test("ERM invalid notation override returns WebRenderResult.Error") {
            val result = WebRenderPipeline.render(ermScript, "svg", null, null, notation = "bogus")
            result.shouldBeInstanceOf<WebRenderResult.Error>()
            result.message shouldContain "notation"
        }

        test("ERM latex format returns WebRenderResult.Error") {
            val result = WebRenderPipeline.render(ermScript, "latex", null, null)
            result.shouldBeInstanceOf<WebRenderResult.Error>()
            result.message.shouldNotBeEmpty()
        }
    })
