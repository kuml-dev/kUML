package dev.kuml.desktop.render

import dev.kuml.cli.ValidationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The desktop `validate` shows exactly the findings of `ValidationService` (the service `kuml validate` uses). */
class DesktopValidationParityTest :
    FunSpec({
        val scripts =
            mapOf(
                "unknown note anchor" to
                    """
                    sysml2Model(name = "N") {
                        val a = stateDef(name = "A", isInitial = true)
                        stmDiagram(name = "S") {
                            include(a)
                            comment(text = "SECRET-BODY", "Gibtsnicht", id = "n1")
                        }
                    }
                    """.trimIndent(),
                "typo on line 5" to
                    """
                    classDiagram(name = "A") {
                        val a = classOf(name = "A")
                        val b = classOf(name = "B")
                        association(source = a, target = b)
                        val c = classOfX(name = "C")
                    }
                    """.trimIndent(),
                "type mismatch" to
                    """
                    classDiagram(name = "A") {
                        classOf(name = 5)
                    }
                    """.trimIndent(),
                "ignored layout option" to
                    """
                    sequenceDiagram(name = "S") {
                        val a = lifeline(name = "A")
                        val b = lifeline(name = "B")
                        message(from = a, to = b, label = "go()")
                        layout { wrapColumns = 3 }
                    }
                    """.trimIndent(),
            )
        scripts.forEach { (name, script) ->
            test("$name: desktop and CLI service report the same findings field by field") {
                val cli = ValidationService.validate(script = script, fileName = "inline.kuml.kts")
                val desktop = DesktopRenderPipeline.validate(script = script).diagnostics
                desktop.map { Triple(it.severity, it.message, it.line to it.column) } shouldBe
                    cli.map { Triple(it.severity.name, it.text(), it.line to it.column) }
            }
        }
    })
