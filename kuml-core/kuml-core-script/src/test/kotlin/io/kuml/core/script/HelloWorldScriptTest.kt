package io.kuml.core.script

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Phase 0 acceptance tests.
 *
 * Goal: `diagram(name = "Test") { }` in a `*.kuml.kts` script compiles
 * and evaluates without errors.
 */
class HelloWorldScriptTest : FunSpec({

    test("minimal diagram script compiles and runs without errors") {
        val result =
            KumlScriptHost.eval(
                code = """diagram(name = "Hello kUML") {}""",
            )
        val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        errors.shouldBeEmpty()
    }

    test("diagram with explicit DiagramType compiles") {
        val result =
            KumlScriptHost.eval(
                code =
                    """
                    diagram(
                        name = "Phase 0 Smoke Test",
                        type = DiagramType.USE_CASE,
                    )
                    """.trimIndent(),
            )
        val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        errors.shouldBeEmpty()
    }

    test("all V1 DiagramType values are accessible in script scope") {
        val result =
            KumlScriptHost.eval(
                code =
                    """
                    val types = listOf(
                        DiagramType.CLASS,
                        DiagramType.SEQUENCE,
                        DiagramType.STATE,
                        DiagramType.COMPONENT,
                        DiagramType.USE_CASE,
                    )
                    check(types.size == 5)
                    """.trimIndent(),
            )
        val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        errors.shouldBeEmpty()
    }

    test("script syntax error is reported as ERROR diagnostic") {
        val result =
            KumlScriptHost.eval(
                code = """this is not valid kotlin!!!""",
                fileName = "invalid.kuml.kts",
            )
        val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        errors.size shouldBe 1
    }
})
