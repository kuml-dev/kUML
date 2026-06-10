package dev.kuml.jetbrains.folding

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class KumlFoldingExtractorTest :
    FunSpec({

        // ── Basic single-block extraction ──────────────────────────────────────

        test("extracts classOf block") {
            val text =
                """
                classOf(name = "User") {
                    attributeDef("id")
                }
                """.trimIndent()

            val candidates = KumlFoldingExtractor.candidates(text)

            // Only classOf has a lambda block; attributeDef("id") has no `{` so it is not folded.
            candidates shouldHaveSize 1
            val classOf = candidates.first { it.callName == "classOf" }
            classOf.placeholder shouldBe """classOf("User") {…}"""
            text[classOf.lambdaStartOffset] shouldBe '{'
            text[classOf.lambdaEndOffset] shouldBe '}'
        }

        test("extracts stateMachine block") {
            val text =
                """
                stateMachine("OrderFSM") {
                    stateDef("Idle")
                }
                """.trimIndent()

            val candidates = KumlFoldingExtractor.candidates(text)

            val sm = candidates.first { it.callName == "stateMachine" }
            sm.placeholder shouldBe """stateMachine("OrderFSM") {…}"""
            text[sm.lambdaStartOffset] shouldBe '{'
            text[sm.lambdaEndOffset] shouldBe '}'
        }

        // ── Nested structure ───────────────────────────────────────────────────

        test("extracts nested sysml2Model with multiple diagrams") {
            val text =
                """
                sysml2Model {
                    partDef("Vehicle") {
                        portDef("powerIn")
                    }
                    bdd("Structure") {
                        partDef("Engine")
                    }
                }
                """.trimIndent()

            val candidates = KumlFoldingExtractor.candidates(text)

            val names = candidates.map { it.callName }.toSet()
            withClue("expected sysml2Model") { names shouldContain "sysml2Model" }
            withClue("expected partDef") { names shouldContain "partDef" }
            withClue("expected bdd") { names shouldContain "bdd" }

            // The outer sysml2Model block must span more characters than the inner ones.
            val outer = candidates.first { it.callName == "sysml2Model" }
            val inner = candidates.first { it.callName == "bdd" }
            outer.lambdaEndOffset shouldBeGreaterThan inner.lambdaEndOffset
        }

        test("bracket counter handles nested blocks") {
            val text =
                """
                umlModel {
                    classOf(name = "A") {
                        classOf(name = "B") {
                        }
                    }
                }
                """.trimIndent()

            val candidates = KumlFoldingExtractor.candidates(text)

            // The outer umlModel close brace must be the very last `}` in the text.
            val outer = candidates.first { it.callName == "umlModel" }
            outer.lambdaEndOffset shouldBe text.lastIndexOf('}')
        }

        // ── Placeholder text ───────────────────────────────────────────────────

        test("placeholder uses first string argument") {
            val text = """partDef("Vehicle") { }"""
            val candidates = KumlFoldingExtractor.candidates(text)

            candidates shouldHaveSize 1
            candidates.first().placeholder shouldBe """partDef("Vehicle") {…}"""
        }

        test("placeholder shows ellipsis when no string arg") {
            val text = """umlModel { }"""
            val candidates = KumlFoldingExtractor.candidates(text)

            candidates shouldHaveSize 1
            candidates.first().placeholder shouldBe "umlModel {…}"
        }

        // ── Filtering unknown names ────────────────────────────────────────────

        test("does not fold unknown DSL functions") {
            val text =
                """
                unknownCall("foo") {
                    doSomething()
                }
                """.trimIndent()

            val candidates = KumlFoldingExtractor.candidates(text)
            candidates.shouldBeEmpty()
        }

        // ── Multiple top-level calls ───────────────────────────────────────────

        test("handles multiple top-level calls") {
            val text =
                """
                classOf(name = "Order") {
                }
                interfaceOf(name = "Payable") {
                }
                """.trimIndent()

            val candidates = KumlFoldingExtractor.candidates(text)

            val names = candidates.map { it.callName }
            withClue("classOf expected") { names shouldContain "classOf" }
            withClue("interfaceOf expected") { names shouldContain "interfaceOf" }
            // Candidates are in source order — classOf comes first.
            candidates.first().callName shouldBe "classOf"
        }

        // ── Empty lambda body ──────────────────────────────────────────────────

        test("handles empty lambda body") {
            val text = """c4Model { }"""
            val candidates = KumlFoldingExtractor.candidates(text)

            candidates shouldHaveSize 1
            val c = candidates.first()
            c.callName shouldBe "c4Model"
            text[c.lambdaStartOffset] shouldBe '{'
            text[c.lambdaEndOffset] shouldBe '}'
        }

        // ── Individual DSL names ───────────────────────────────────────────────

        test("extracts c4Model block") {
            val text =
                """
                c4Model {
                    diagram("Context") { }
                }
                """.trimIndent()

            val candidates = KumlFoldingExtractor.candidates(text)

            candidates.map { it.callName } shouldContain "c4Model"
            candidates.map { it.callName } shouldContain "diagram"
        }

        test("extracts actDiagram and stmDiagram") {
            val text =
                """
                actDiagram("Checkout") { }
                stmDiagram("OrderFSM") { }
                """.trimIndent()

            val candidates = KumlFoldingExtractor.candidates(text)

            candidates shouldHaveSize 2
            candidates.map { it.callName }.toSet() shouldBe setOf("actDiagram", "stmDiagram")
        }

        // ── Whitespace tolerance ───────────────────────────────────────────────

        test("handles whitespace between call and lambda") {
            // DSL writers sometimes add a newline between the call head and `{`.
            val text =
                """
                umlModel
                    {
                    classOf(name = "Foo") { }
                }
                """.trimIndent()

            val candidates = KumlFoldingExtractor.candidates(text)

            candidates.map { it.callName } shouldContain "umlModel"
        }

        // ── extractFirstStringArg ──────────────────────────────────────────────

        test("extractFirstStringArg returns null for empty arg list") {
            KumlFoldingExtractor.extractFirstStringArg("") shouldBe null
            KumlFoldingExtractor.extractFirstStringArg("   ") shouldBe null
        }

        test("extractFirstStringArg extracts named parameter") {
            KumlFoldingExtractor.extractFirstStringArg("""(name = "Order")""") shouldBe "Order"
        }

        test("extractFirstStringArg extracts positional parameter") {
            KumlFoldingExtractor.extractFirstStringArg("""("Vehicle")""") shouldBe "Vehicle"
        }
    })
