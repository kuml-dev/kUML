package dev.kuml.core.script.diagnostics

import dev.kuml.core.script.KumlScriptHost
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class ScriptProblemsTest :
    FunSpec({
        fun problems(code: String) =
            ScriptProblems.describe(
                result = KumlScriptHost.eval(code = code, fileName = "x.kuml.kts"),
                source = code,
                scriptFileName = "x.kuml.kts",
            )

        test("an unresolved reference carries its line and is a DSL_ERROR") {
            val p =
                problems(
                    """
                    classDiagram(name = "A") {
                        val a = classOf(name = "A")
                        val b = classOfX(name = "B")
                    }
                    """.trimIndent(),
                ).first()
            p.code shouldBe ScriptProblemCode.DSL_ERROR
            p.line shouldBe 3
        }

        test("a type mismatch names the enclosing call") {
            val p =
                problems(
                    """
                    classDiagram(name = "A") {
                        classOf(name = 5)
                    }
                    """.trimIndent(),
                ).first()
            p.code shouldBe ScriptProblemCode.TYPE_MISMATCH
            p.line shouldBe 2
            p.function shouldBe "classOf"
        }

        test("a runtime exception inside a successful evaluation is reported with its script line") {
            val p =
                problems(
                    """
                    val x = 1
                    error("boom")
                    classDiagram(name = "A") { }
                    """.trimIndent(),
                ).first()
            p.line shouldBe 2
            p.message shouldNotContain "/"
        }

        test("enclosingCall skips brackets inside strings and comments") {
            val src = "foo(\"a)b\", // )\n   bar(1, baz)"
            ScriptProblems.enclosingCall(source = src, line = 2, column = 12) shouldBe "bar"
            ScriptProblems.enclosingCall(source = src, line = 1, column = 6) shouldBe "foo"
        }

        test("classify recognises the receiver-scope message") {
            ScriptProblems.classify("'fun x(): Unit' cannot be called in this context with an implicit receiver.") shouldBe
                ScriptProblemCode.RECEIVER_SCOPE
        }
    })
