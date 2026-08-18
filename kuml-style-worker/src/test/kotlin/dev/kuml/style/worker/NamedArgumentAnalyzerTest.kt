package dev.kuml.style.worker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * End-to-end tests of the worker's analysis path: [wrapKumlScript] →
 * [KumlAnalysisSession.analyzeWithSourceFixtures] → [mapToOriginalLineColumn].
 *
 * The exemption-logic cases mirror `dev.kuml.detekt.RequireNamedArgumentsSpec`
 * (`:kuml-detekt-rules`) one-for-one — that spec is the reference behaviour
 * this worker reimplements against real source text instead of detekt's
 * pipeline; keeping the case list in lockstep is the whole point of the
 * duplication (see [analyzeNamedArguments]'s KDoc).
 *
 * `dev.kuml.fixture` — the fake "owned" package under test — is supplied as
 * an in-source fixture (via [KumlAnalysisSession.analyzeWithSourceFixtures]),
 * never a real `kuml-*` jar; see that function's KDoc for why.
 */
class NamedArgumentAnalyzerTest :
    FunSpec({

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

        fun findingsFor(snippet: String): List<NamedArgumentFinding> {
            val original = "import dev.kuml.fixture.*\n$snippet"
            val wrapped = wrapKumlScript(original)
            return KumlAnalysisSession.analyzeWithSourceFixtures(wrapped = wrapped, fixtureSources = listOf(kumlFixture))
        }

        // ── happy path ───────────────────────────────────────────────────────
        test("flags every positional argument of a 3-parameter kUML function") {
            val findings = findingsFor("""val x = many("q", 1, true)""")
            findings shouldHaveSize 3
        }

        test("flags positional arguments of a kUML constructor") {
            findingsFor("""val p = Point(1, 2)""") shouldHaveSize 2
        }

        test("does not flag a fully named call") {
            findingsFor("""val x = many(alpha = "q", beta = 1, gamma = true)""") shouldHaveSize 0
        }

        // ── exemptions ───────────────────────────────────────────────────────
        test("single-value-parameter function is exempt") {
            findingsFor("""val x = single("only")""") shouldHaveSize 0
        }

        test("kotlin stdlib call is exempt") {
            findingsFor("""val x = listOf(1, 2, 3)""") shouldHaveSize 0
        }

        test("non-owned package call is exempt") {
            findingsFor("""val x = "abcdef".substring(0, 2)""") shouldHaveSize 0
        }

        test("trailing lambda of a block-DSL call is exempt, its value args are not") {
            val findings = findingsFor("""val x = state("Idle", 2) { }""")
            findings shouldHaveSize 2 // name + depth, NOT the lambda
            findings.none { it.paramName == "body" } shouldBe true
        }

        test("lone trailing lambda is exempt") {
            findingsFor("""val x = blockOnly { }""") shouldHaveSize 0
        }

        test("operator function is exempt") {
            findingsFor("""val x = Box(v = 1) + Box(v = 2)""") shouldHaveSize 0
        }

        test("infix function is exempt") {
            findingsFor("""val x = Box(v = 1) combine Box(v = 2)""") shouldHaveSize 0
        }

        test("vararg elements are exempt, the leading fixed parameter is not") {
            val findings = findingsFor("""val x = varargFn("q", 1, 2, 3)""")
            findings shouldHaveSize 1
            findings.single().paramName shouldBe "first"
        }

        // ── wrapper-specific cases ───────────────────────────────────────────
        test("empty script yields no findings and does not throw") {
            findingsFor("") shouldHaveSize 0
        }

        test("script with an @file: suppress annotation still resolves the body") {
            val original = "import dev.kuml.fixture.*\n@file:Suppress(\"unused\")\nval x = many(\"q\", 1, true)"
            val wrapped = wrapKumlScript(original)
            val findings = KumlAnalysisSession.analyzeWithSourceFixtures(wrapped = wrapped, fixtureSources = listOf(kumlFixture))
            findings shouldHaveSize 3
        }

        test("syntax error later in the script does not suppress findings from the valid part") {
            // The Analysis API tolerates partial parse errors — a call before
            // the broken part must still be resolved and flagged, mirroring
            // the plan's broken-unresolved.kuml.kts finding.
            val findings = findingsFor("val x = many(\"q\", 1, true)\nval y = )(((garbage")
            findings.size shouldBe 3
        }

        // ── offset mapping ───────────────────────────────────────────────────
        test("reported line/column map back exactly to the original source") {
            val original = "import dev.kuml.fixture.*\nval x = 1\nval p = Point(1, 2)\n"
            val wrapped = wrapKumlScript(original)
            val findings = KumlAnalysisSession.analyzeWithSourceFixtures(wrapped = wrapped, fixtureSources = listOf(kumlFixture))
            findings shouldHaveSize 2

            val mapped =
                findings
                    .map { f ->
                        mapToOriginalLineColumn(
                            originalSource = original,
                            offsetInWrappedSource = f.offsetInWrappedSource,
                            prefixLen = wrapped.prefixLen,
                        )
                    }.sortedBy { it.second }
            // "val p = Point(1, 2)" — 1-based columns: 'P'=9 … '('=14, so the
            // first argument '1' starts at column 15, the second '2' at 18.
            mapped[0] shouldBe (3 to 15)
            mapped[1] shouldBe (3 to 18)
        }
    })
