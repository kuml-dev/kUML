package dev.kuml.core.script

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Phase 0 acceptance tests.
 *
 * Goal: `diagram(name = "Test") { }` in a `*.kuml.kts` script compiles
 * and evaluates without errors.
 */
class HelloWorldScriptTest : FunSpec(body = {

    test(name = "minimal diagram script compiles and runs without errors") {
        val result =
            KumlScriptHost.eval(
                code = """diagram(name = "Hello kUML") {}""",
            )
        val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        errors.shouldBeEmpty()
    }

    test(name = "diagram with explicit DiagramType compiles") {
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

    test(name = "all V1 DiagramType values are accessible in script scope") {
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

    test(name = "script syntax error is reported as ERROR diagnostic") {
        val result =
            KumlScriptHost.eval(
                code = """this is not valid kotlin!!!""",
                fileName = "invalid.kuml.kts",
            )
        val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        // Kotlin Scripting kann mehrere Fehler pro syntaktisch ungültigem Skript zurückgeben
        // (z.B. einen pro Token). Wichtig ist nur: mindestens einer wird gemeldet.
        errors.shouldNotBeEmpty()
    }
})
