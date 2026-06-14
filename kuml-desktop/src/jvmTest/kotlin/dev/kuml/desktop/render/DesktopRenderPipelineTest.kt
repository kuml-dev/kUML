package dev.kuml.desktop.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class DesktopRenderPipelineTest : FunSpec({

    test("render with empty script returns Error") {
        DesktopRenderPipeline.render("", "plain").shouldBeInstanceOf<DesktopRenderResult.Error>()
    }

    test("render with invalid kotlin returns Error") {
        DesktopRenderPipeline.render("this is not valid kotlin @@@@", "plain")
            .shouldBeInstanceOf<DesktopRenderResult.Error>()
    }

    test("render valid UML classDiagram returns Svg") {
        val script = """
            classDiagram(name = "Test") {
                val a = classOf(name = "Foo") {
                    attribute(name = "id", type = "Long")
                }
            }
        """.trimIndent()
        val result = DesktopRenderPipeline.render(script, "plain")
        result.shouldBeInstanceOf<DesktopRenderResult.Svg>()
        (result as DesktopRenderResult.Svg).svg shouldContain "<svg"
    }

    test("render with unknown theme falls back to plain") {
        val script = """
            classDiagram(name = "Test") {
                val b = classOf(name = "Bar") { }
            }
        """.trimIndent()
        DesktopRenderPipeline.render(script, "nonexistent-theme-xyz")
            .shouldBeInstanceOf<DesktopRenderResult.Svg>()
    }

    test("multiple renders are idempotent") {
        val script = """
            classDiagram(name = "Test") {
                val c = classOf(name = "Baz") { }
            }
        """.trimIndent()
        val r1 = DesktopRenderPipeline.render(script, "plain") as DesktopRenderResult.Svg
        val r2 = DesktopRenderPipeline.render(script, "plain") as DesktopRenderResult.Svg
        r1.svg shouldBe r2.svg
    }

    test("DesktopEngineInit.ensure() is idempotent") {
        repeat(3) { DesktopEngineInit.ensure() }
        // Kein Crash = Erfolg
    }

    test("SVG contains class name Fahrzeug") {
        val script = """
            import dev.kuml.uml.*
            classDiagram(name = "Test") {
                classOf(name = "Fahrzeug") { }
            }
        """.trimIndent()
        val result = DesktopRenderPipeline.render(script, "plain") as DesktopRenderResult.Svg
        result.svg shouldContain "Fahrzeug"
    }

    test("SVG contains both class names when two classes defined") {
        val script = """
            import dev.kuml.uml.*
            classDiagram(name = "Test") {
                val a = classOf(name = "Alpha") { }
                val b = classOf(name = "Beta") { }
                association(source = a, target = b)
            }
        """.trimIndent()
        val result = DesktopRenderPipeline.render(script, "plain") as DesktopRenderResult.Svg
        result.svg shouldContain "Alpha"
        result.svg shouldContain "Beta"
    }

    test("SVG contains attribute name") {
        val script = """
            import dev.kuml.uml.*
            classDiagram(name = "Test") {
                classOf(name = "Person") {
                    attribute(name = "email", type = "String")
                }
            }
        """.trimIndent()
        val result = DesktopRenderPipeline.render(script, "plain") as DesktopRenderResult.Svg
        result.svg shouldContain "email"
    }

    test("render dark theme returns Svg") {
        val script = """
            import dev.kuml.uml.*
            classDiagram(name = "Dark") {
                classOf(name = "A") { }
            }
        """.trimIndent()
        DesktopRenderPipeline.render(script, "dark").shouldBeInstanceOf<DesktopRenderResult.Svg>()
    }
})
