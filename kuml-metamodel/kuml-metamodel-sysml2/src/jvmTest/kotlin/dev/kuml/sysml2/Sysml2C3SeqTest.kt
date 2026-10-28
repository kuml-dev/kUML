package dev.kuml.sysml2

import dev.kuml.sysml2.constraint.Sysml2SeqChecker
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

/** Wave C3 — SysML 2 SEQ: nested fragments, ref, time constructs, found/lost: DSL, printer, JSON, checker. */
class Sysml2C3SeqTest :
    FunSpec({
        fun sample(): Sysml2Model =
            sysml2Model(name = "C3") {
                val a = lifelineDef(name = "a")
                val b = lifelineDef(name = "b")
                message(label = "m0", source = a, target = b, seqNo = 0)
                foundMessage(label = "ping", target = b, seqNo = 1)
                lostMessage(label = "drop", source = a, seqNo = 2)
                combinedFragment(name = "outer", operator = CombinedFragmentOperator.Loop) {
                    operand(guard = "g", start = 3, end = 6) {
                        combinedFragment(name = "inner", operator = CombinedFragmentOperator.Opt) {
                            operand(guard = "h", start = 4, end = 5)
                        }
                    }
                }
                interactionUse(name = "auth", refersTo = "Authenticate", lifelines = listOf(a, b), seqNo = 8)
                durationConstraint(lifeline = a, from = 0, to = 2, expression = "0..3s")
                timeObservation(lifeline = b, seqNo = 0, name = "t0")
                seqDiagram(name = "S") {
                    include(a)
                    include(b)
                }
            }

        val model = sample()
        val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()

        test("DSL registers nested fragments with parent links") {
            val inner = model.usages.filterIsInstance<CombinedFragmentUsage>().single { it.name == "inner" }
            inner.parentFragmentId shouldBe "combinedFragment:outer"
            inner.parentOperandIndex shouldBe 0
            model.usages
                .filterIsInstance<CombinedFragmentUsage>()
                .single { it.name == "outer" }
                .parentFragmentId shouldBe null
            model.usages.filterIsInstance<MessageUsage>().map { it.kind } shouldContainExactly
                listOf(MessageKind.Sync, MessageKind.Found, MessageKind.Lost)
        }

        test("printer emits every new construct in id form") {
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "parentFragmentId = \"combinedFragment:outer\""
            dsl shouldContain "interactionUseById("
            dsl shouldContain "durationConstraintById("
            dsl shouldContain "timeObservationById("
            dsl shouldContain "MessageKind.Found"
            dsl shouldContain "MessageKind.Lost"
            Sysml2DslPrinter.print(sysml2Model(name = "P") { lifelineDef(name = "x") }) shouldNotContain "parentFragmentId"
        }

        test("JSON round-trips and an old fragment without the new fields still decodes") {
            val json = Json.encodeToString(Sysml2Model.serializer(), model)
            Json.decodeFromString<Sysml2Model>(json) shouldBe model
            val old =
                """{"type":"dev.kuml.sysml2.CombinedFragmentUsage","id":"f","name":"f","operator":"Opt",""" +
                    """"operands":[{"startSeqNo":0,"endSeqNo":1}]}"""
            val u = Json.decodeFromString(Sysml2Usage.serializer(), old) as CombinedFragmentUsage
            u.parentFragmentId shouldBe null
            u.parentOperandIndex shouldBe null
            // default encoding (encodeDefaults = false) leaves the new null fields out -> old shape is byte-identical
            Json.encodeToString(Sysml2Usage.serializer(), u) shouldNotContain "parentFragmentId"
        }

        test("checker: a clean model has no findings") {
            Sysml2SeqChecker.check(model = model, diagram = seq).shouldBeEmpty()
        }

        fun codes(m: Sysml2Model): List<Int> =
            Sysml2SeqChecker.check(model = m, diagram = m.diagrams.filterIsInstance<SeqDiagram>().single()).map {
                it.code
            }

        fun base(block: dev.kuml.sysml2.dsl.Sysml2ModelBuilder.(LifelineDefinition, LifelineDefinition) -> Unit): Sysml2Model =
            sysml2Model(name = "X") {
                val a = lifelineDef(name = "a")
                val b = lifelineDef(name = "b")
                block(a, b)
                seqDiagram(name = "S") {
                    include(a)
                    include(b)
                }
            }

        test("checker 401/402/403/404/405") {
            codes(
                base { _, _ ->
                    combinedFragment(name = "p", operator = CombinedFragmentOperator.Opt, startSeqNo = 0, endSeqNo = 2, guard = "g")
                    combinedFragment(
                        name = "c",
                        operator = CombinedFragmentOperator.Opt,
                        operands = listOf(CombinedFragmentOperand(guard = "g", startSeqNo = 1, endSeqNo = 5)),
                        id = "c",
                        parentFragmentId = "combinedFragment:p",
                        parentOperandIndex = 0,
                    )
                },
            ) shouldContain 401
            codes(
                base {
                    _,
                    _,
                    ->
                    combinedFragment(
                        name = "c",
                        operator = CombinedFragmentOperator.Opt,
                        operands = listOf(CombinedFragmentOperand(guard = "g", startSeqNo = 1, endSeqNo = 5)),
                        id = "c",
                        parentFragmentId = "nope",
                        parentOperandIndex = 0,
                    )
                },
            ) shouldContain
                402
            codes(
                base { _, _ ->
                    for (i in 0..9) {
                        combinedFragment(
                            name = "f$i",
                            operator = CombinedFragmentOperator.Opt,
                            operands = listOf(CombinedFragmentOperand(guard = "g", startSeqNo = 0, endSeqNo = 1)),
                            id = "f$i",
                            parentFragmentId = if (i == 0) null else "f${i - 1}",
                            parentOperandIndex = if (i == 0) null else 0,
                        )
                    }
                },
            ) shouldContain 403
            codes(
                base { _, _ ->
                    for (i in 0..256) {
                        combinedFragment(
                            name = "f$i",
                            operator = CombinedFragmentOperator.Opt,
                            startSeqNo = 0,
                            endSeqNo = 1,
                            guard = "g",
                            id = "f$i",
                        )
                    }
                },
            ) shouldContain 404
            codes(
                base { _, _ ->
                    combinedFragment(
                        name = "f",
                        operator = CombinedFragmentOperator.Par,
                        operands =
                            List(17) {
                                CombinedFragmentOperand(guard = "g", startSeqNo = it, endSeqNo = it)
                            },
                    )
                },
            ) shouldContain 405
        }

        test("checker: usages of another diagram's lifelines are not reported (no 406)") {
            val m =
                sysml2Model(name = "Two") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    val c = lifelineDef(name = "c")
                    val d = lifelineDef(name = "d")
                    interactionUse(name = "r", refersTo = "X", lifelines = listOf(a, b), seqNo = 3)
                    durationConstraint(lifeline = a, from = 0, to = 1, expression = "1s")
                    timeObservation(lifeline = a, seqNo = 0, name = "t")
                    seqDiagram(name = "Second") {
                        include(c)
                        include(d)
                    }
                    seqDiagram(name = "First") {
                        include(a)
                        include(b)
                    }
                }
            val second = m.diagrams.filterIsInstance<SeqDiagram>().single { it.name == "Second" }
            Sysml2SeqChecker.check(model = m, diagram = second).map { it.code } shouldBe emptyList()
        }

        test("checker 406/407/410/411") {
            codes(base { a, _ -> interactionUseById(name = "r", refersTo = "X", lifelineIds = listOf("ghost"), seqNo = 3) }) shouldContain
                406
            codes(
                base { a, b ->
                    message(label = "m", source = a, target = b, seqNo = 2)
                    interactionUse(name = "r", refersTo = "X", lifelines = listOf(a), seqNo = 2)
                },
            ) shouldContain 407
            codes(base { a, _ -> durationConstraint(lifeline = a, from = 0, to = 1, expression = "x".repeat(65)) }) shouldContain 410
            codes(
                base {
                    a,
                    b,
                    ->
                    messageById(label = "f", sourceLifelineId = a.id, targetLifelineId = b.id, seqNo = 0, kind = MessageKind.Found)
                },
            ) shouldContain
                411
        }
    })
