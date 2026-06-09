package dev.kuml.runtime.sysml2

import dev.kuml.runtime.TraceEntry
import dev.kuml.sysml2.ActivityNodeKind
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * V2.0.18 — adapter tests for [Sysml2ActivityAdapter].
 *
 * Each test builds a small SysML 2 model via the public DSL and asserts on
 * the translated [dev.kuml.runtime.activity.ActivityRuntimeSpec] or on the
 * runtime execution result.
 */
class Sysml2ActivityAdapterTest :
    FunSpec({

        // ── 1. Single Action node ─────────────────────────────────────────────

        test("single Action node returns runtime with one node and zero edges") {
            val model =
                sysml2Model("SingleAction") {
                    val act = actionDef("DoSomething", action = "doIt()")
                    actDiagram("SingleAction ACT") {
                        include(act)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            val spec = Sysml2ActivityAdapter.toSpec(model, diagram)

            spec.nodes.size shouldBe 1
            spec.edges shouldHaveSize 0
            val nodeSpec = spec.nodes.values.first()
            nodeSpec.kind shouldBe ActivityNodeKind.Action
            nodeSpec.actionBody shouldBe "doIt()"
        }

        // ── 2. Adapter reads ControlFlowUsage.guard ───────────────────────────

        test("adapter reads ControlFlowUsage guard correctly") {
            val model =
                sysml2Model("GuardedFlow") {
                    val init = initialNode()
                    val act = actionDef("A")
                    val fin = finalNode()
                    controlFlow("cf1", init, act, guard = "valid")
                    controlFlow("cf2", act, fin)
                    actDiagram("Guarded ACT") {
                        include(init)
                        include(act)
                        include(fin)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            val spec = Sysml2ActivityAdapter.toSpec(model, diagram)

            val guardedEdge = spec.edges.first { it.guard != null }
            guardedEdge.guard shouldBe "valid"
        }

        // ── 3. Adapter reads ObjectFlowUsage.objectType ───────────────────────

        test("adapter reads ObjectFlowUsage objectType correctly") {
            val model =
                sysml2Model("ObjFlow") {
                    val a = actionDef("Source")
                    val b = actionDef("Target")
                    objectFlow("of1", a, b, objectType = "Order")
                    actDiagram("ObjFlow ACT") {
                        include(a)
                        include(b)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            val spec = Sysml2ActivityAdapter.toSpec(model, diagram)

            spec.edges shouldHaveSize 1
            val edge = spec.edges.first()
            edge.isObjectFlow shouldBe true
            edge.objectType shouldBe "Order"
        }

        // ── 4. Initial node detected by ActivityNodeKind ──────────────────────

        test("Initial node detected correctly by kind") {
            val model =
                sysml2Model("WithInitial") {
                    val init = initialNode()
                    val act = actionDef("A")
                    controlFlow("cf1", init, act)
                    actDiagram("WithInitial ACT") {
                        include(init)
                        include(act)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            val spec = Sysml2ActivityAdapter.toSpec(model, diagram)

            val initialNodes = spec.nodes.values.filter { it.kind == ActivityNodeKind.Initial }
            initialNodes shouldHaveSize 1
        }

        // ── 5. Edge dropped when source not in diagram ────────────────────────

        test("edge dropped when source not in diagram elementIds") {
            val model =
                sysml2Model("FilterSource") {
                    val hidden = actionDef("Hidden")
                    val visible = actionDef("Visible")
                    controlFlow("cf1", hidden, visible)
                    actDiagram("FilterSource ACT") {
                        include(visible)
                        // hidden NOT included
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            val spec = Sysml2ActivityAdapter.toSpec(model, diagram)

            spec.edges shouldHaveSize 0
        }

        // ── 6. Edge dropped when target not in diagram ────────────────────────

        test("edge dropped when target not in diagram elementIds") {
            val model =
                sysml2Model("FilterTarget") {
                    val visible = actionDef("Visible")
                    val hidden = actionDef("Hidden")
                    controlFlow("cf1", visible, hidden)
                    actDiagram("FilterTarget ACT") {
                        include(visible)
                        // hidden NOT included
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            val spec = Sysml2ActivityAdapter.toSpec(model, diagram)

            spec.edges shouldHaveSize 0
        }

        // ── 7. Full order-processing fixture produces correct node + edge count ─

        test("full order-processing example produces correct node and edge counts") {
            val model =
                sysml2Model("OrderProcessing") {
                    val init = initialNode()
                    val place = actionDef("PlaceOrder", action = "submit(order)")
                    val validate = actionDef("ValidateOrder", action = "validate(order)")
                    val decide = decisionNode("valid?")
                    val pay = actionDef("ProcessPayment", action = "charge(total)")
                    val cancel = actionDef("CancelOrder", action = "notify(cancelled)")
                    val fin = finalNode()
                    val ff = flowFinalNode()

                    controlFlow("start", init, place)
                    controlFlow("placed", place, validate)
                    controlFlow("validated", validate, decide)
                    controlFlow("yes", decide, pay, guard = "valid")
                    controlFlow("no", decide, cancel, guard = "!valid")
                    controlFlow("payEnd", pay, fin)
                    controlFlow("cancelEnd", cancel, ff)

                    actDiagram("Order ACT") {
                        include(init)
                        include(place)
                        include(validate)
                        include(decide)
                        include(pay)
                        include(cancel)
                        include(fin)
                        include(ff)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            val spec = Sysml2ActivityAdapter.toSpec(model, diagram)

            spec.nodes.size shouldBe 8
            spec.edges shouldHaveSize 7
        }

        // ── 8. Multi-initial model ─────────────────────────────────────────────

        test("multi-initial model has two Initial nodes in spec") {
            val model =
                sysml2Model("MultiInitial") {
                    // Use unique IDs to prevent id-collision in associateBy
                    val i1 = initialNode(id = "Init1")
                    val i2 = initialNode(id = "Init2")
                    val a = actionDef("A")
                    val b = actionDef("B")
                    val fin = finalNode()
                    controlFlow("c1", i1, a)
                    controlFlow("c2", i2, b)
                    controlFlow("c3", a, fin)
                    controlFlow("c4", b, fin)
                    actDiagram("Multi ACT") {
                        include(i1)
                        include(i2)
                        include(a)
                        include(b)
                        include(fin)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            val spec = Sysml2ActivityAdapter.toSpec(model, diagram)

            val initialNodes = spec.nodes.values.filter { it.kind == ActivityNodeKind.Initial }
            initialNodes shouldHaveSize 2
        }

        // ── V2.0.20b: ACT guard pre-parse tests ──────────────────────────────

        test("adapter pre-parses ControlFlow guards — parseable guard does not throw") {
            val model =
                sysml2Model("PreParseGuard") {
                    val init = initialNode()
                    val act = actionDef("A")
                    val fin = finalNode()
                    // "valid" is a simple IDENT — parseable by OclLikeExpressionParser
                    controlFlow("c1", init, act, guard = "valid")
                    controlFlow("c2", act, fin)
                    actDiagram("PreParse ACT") {
                        include(init)
                        include(act)
                        include(fin)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            // runtimeFor must not throw even when guards are pre-parsed
            val runtime = Sysml2ActivityAdapter.runtimeFor(model, diagram)
            // Spec is correctly built with the guard on the edge
            val guardedEdge = runtime.spec.edges.first { it.guard != null }
            guardedEdge.guard shouldBe "valid"
        }

        test("adapter tolerates unparseable guard at construction — does not throw") {
            val model =
                sysml2Model("UnparseableGuard") {
                    val init = initialNode()
                    val act = actionDef("A")
                    val fin = finalNode()
                    // "@@@" cannot be parsed — should not throw at adapter construction time
                    controlFlow("c1", init, act, guard = "@@@")
                    controlFlow("c2", act, fin)
                    actDiagram("Unparseable ACT") {
                        include(init)
                        include(act)
                        include(fin)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            // Must NOT throw — unparseable guards are silently ignored at construction
            val runtime = Sysml2ActivityAdapter.runtimeFor(model, diagram)
            val guardedEdge = runtime.spec.edges.first { it.guard != null }
            guardedEdge.guard shouldBe "@@@"
        }

        // ── bonus: full run-to-termination via runtimeFor ─────────────────────

        test("runtimeFor produces a runtime that runs order-processing to termination") {
            val model =
                sysml2Model("RunTest") {
                    val init = initialNode()
                    val act = actionDef("Work", action = "work()")
                    val fin = finalNode()
                    controlFlow("c1", init, act)
                    controlFlow("c2", act, fin)
                    actDiagram("Run ACT") {
                        include(init)
                        include(act)
                        include(fin)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            val runtime = Sysml2ActivityAdapter.runtimeFor(model, diagram)
            val (instance, trace) = runtime.run()

            instance.isTerminated shouldBe true
            trace.filterIsInstance<TraceEntry.ActivityTerminated>() shouldHaveSize 1
            trace
                .filterIsInstance<TraceEntry.ActivityActionInvoked>()
                .any { it.body == "work()" } shouldBe true
        }
    })
