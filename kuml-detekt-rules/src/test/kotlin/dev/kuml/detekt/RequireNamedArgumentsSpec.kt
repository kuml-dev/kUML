package dev.kuml.detekt

import dev.detekt.api.Config
import dev.detekt.test.utils.createEnvironment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class RequireNamedArgumentsSpec :
    FunSpec({

        val env = createEnvironment()

        // Compiled alongside every snippet; supplies the "kUML-owned" symbols under
        // the real dev.kuml. prefix so the ownership check is exercised for real.
        val kumlFixture =
            """
            package dev.kuml.fixture

            class Box(val v: Int) {
                operator fun plus(other: Box): Box = Box(v + other.v)
                infix fun combine(other: Box): Box = Box(v + other.v)
            }
            data class Point(val x: Int, val y: Int)
            fun single(only: String): String = only
            fun many(alpha: String, beta: Int, gamma: Boolean = false): String = ""
            fun varargFn(first: String, vararg rest: Int): String = ""
            fun state(name: String, depth: Int, body: () -> Unit) { body() }
            fun blockOnly(body: () -> Unit) { body() }
            """.trimIndent()

        fun check(snippet: String) =
            RequireNamedArguments(Config.empty)
                .lintWithContext(env, "import dev.kuml.fixture.*\n$snippet", kumlFixture)

        // ── happy path ───────────────────────────────────────────────────────
        test("flags every positional argument of a 3-parameter kUML function") {
            val findings = check("""val x = many("q", 1, true)""")
            findings shouldHaveSize 3
            findings.forEach { it.message.contains("positional") shouldBe true }
        }

        test("flags positional arguments of a kUML constructor") {
            check("""val p = Point(1, 2)""") shouldHaveSize 2
        }

        test("does not flag a fully named call") {
            check("""val x = many(alpha = "q", beta = 1, gamma = true)""") shouldHaveSize 0
        }

        // ── exemptions ───────────────────────────────────────────────────────
        test("single-value-parameter function is exempt") {
            check("""val x = single("only")""") shouldHaveSize 0
        }

        test("kotlin stdlib call is exempt") {
            check("""val x = listOf(1, 2, 3)""") shouldHaveSize 0
        }

        test("non-owned package call is exempt") {
            check("""val x = "abcdef".substring(0, 2)""") shouldHaveSize 0
        }

        test("trailing lambda of a block-DSL call is exempt, its value args are not") {
            val findings = check("""val x = state("Idle", 2) { }""")
            findings shouldHaveSize 2 // name + depth, NOT the lambda
            findings.none { it.message.contains("body") } shouldBe true
        }

        test("lone trailing lambda is exempt") {
            check("""val x = blockOnly { }""") shouldHaveSize 0
        }

        test("operator function is exempt") {
            check("""val x = Box(1) + Box(2)""") shouldHaveSize 0
        }

        test("infix function is exempt") {
            check("""val x = Box(1) combine Box(2)""") shouldHaveSize 0
        }

        test("vararg elements are exempt, the leading fixed parameter is not") {
            val findings = check("""val x = varargFn("q", 1, 2, 3)""")
            findings shouldHaveSize 1
            findings.single().message.contains("'first'") shouldBe true
        }

        test("respects a custom ownedPackagePrefixes configuration") {
            val findings =
                RequireNamedArguments(SimpleTestConfig("ownedPackagePrefixes" to listOf("does.not.match.")))
                    .lintWithContext(env, "import dev.kuml.fixture.*\nval x = many(\"q\", 1, true)", kumlFixture)
            findings shouldHaveSize 0
        }

        // ── autocorrect splice (pure function, see RequireNamedArguments.spliceNamedArguments) ──
        test("splice inserts named-argument prefixes without moving surrounding text") {
            val text = """val x = many("q", 1, true)"""
            val qOffset = text.indexOf("\"q\"")
            val oneOffset = text.indexOf("1, true") // first char of the '1'
            val trueOffset = text.indexOf("true")
            val spliced =
                spliceNamedArguments(
                    text,
                    listOf(
                        qOffset to "alpha = ",
                        oneOffset to "beta = ",
                        trueOffset to "gamma = ",
                    ),
                )
            spliced shouldBe """val x = many(alpha = "q", beta = 1, gamma = true)"""
        }

        test("splice never reorders — insertions preserve original call-site argument order") {
            // Two side-effecting-looking arguments in call-site order; splice must not swap them.
            val text = "f(sideEffect1(), sideEffect2())"
            val firstOffset = text.indexOf("sideEffect1")
            val secondOffset = text.indexOf("sideEffect2")
            val spliced =
                spliceNamedArguments(text, listOf(secondOffset to "b = ", firstOffset to "a = "))
            spliced shouldBe "f(a = sideEffect1(), b = sideEffect2())"
        }
    })
