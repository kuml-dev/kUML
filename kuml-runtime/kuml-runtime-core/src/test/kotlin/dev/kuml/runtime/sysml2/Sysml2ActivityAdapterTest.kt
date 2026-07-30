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
                sysml2Model(name = "SingleAction") {
                    val act = actionDef(name = "DoSomething", action = "doIt()")
                    actDiagram(name = "SingleAction ACT") {
                        include(act)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            val spec = Sysml2ActivityAdapter.toSpec(model = model, diagram = diagram)

            spec.nodes.size shouldBe 1
            spec.edges shouldHaveSize 0
            val nodeSpec = spec.nodes.values.first()
            nodeSpec.kind shouldBe ActivityNodeKind.Action
            nodeSpec.actionBody shouldBe "doIt()"
        }

        // ── 2. Adapter reads ControlFlowUsage.guard ───────────────────────────

        test("adapter reads ControlFlowUsage guard correctly") {
            val model =
                sysml2Model(name = "GuardedFlow") {
                    val init = initialNode()
                    val act = actionDef(name = "A")
                    val fin = finalNode()
                    controlFlow(name = "cf1", source = init, target = act, guard = "valid")
                    controlFlow(name = "cf2", source = act, target = fin)
                    actDiagram(name = "Guarded ACT") {
                        include(init)
                        include(act)
                        include(fin)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            val spec = Sysml2ActivityAdapter.toSpec(model = model, diagram = diagram)

            val guardedEdge = spec.edges.first { it.guard != null }
            guardedEdge.guard shouldBe "valid"
        }

        // ── 3. Adapter reads ObjectFlowUsage.objectType ───────────────────────

        test("adapter reads ObjectFlowUsage objectType correctly") {
            val model =
                sysml2Model(name = "ObjFlow") {
                    val a = actionDef(name = "Source")
                    val b = actionDef(name = "Target")
                    objectFlow(name = "of1", source = a, target = b, objectType = "Order")
                    actDiagram(name = "ObjFlow ACT") {
                        include(a)
                        include(b)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            val spec = Sysml2ActivityAdapter.toSpec(model = model, diagram = diagram)

            spec.edges shouldHaveSize 1
            val edge = spec.edges.first()
            edge.isObjectFlow shouldBe true
            edge.objectType shouldBe "Order"
        }

        // ── 4. Initial node detected by ActivityNodeKind ──────────────────────

        test("Initial node detected correctly by kind") {
            val model =
                sysml2Model(name = "WithInitial") {
                    val init = initialNode()
                    val act = actionDef(name = "A")
                    controlFlow(name = "cf1", source = init, target = act)
                    actDiagram(name = "WithInitial ACT") {
                        include(init)
                        include(act)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            val spec = Sysml2ActivityAdapter.toSpec(model = model, diagram = diagram)

            val initialNodes = spec.nodes.values.filter { it.kind == ActivityNodeKind.Initial }
            initialNodes shouldHaveSize 1
        }

        // ── 5. Edge dropped when source not in diagram ────────────────────────

        test("edge dropped when source not in diagram elementIds") {
            val model =
                sysml2Model(name = "FilterSource") {
                    val hidden = actionDef(name = "Hidden")
                    val visible = actionDef(name = "Visible")
                    controlFlow(name = "cf1", source = hidden, target = visible)
                    actDiagram(name = "FilterSource ACT") {
                        include(visible)
                        // hidden NOT included
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            val spec = Sysml2ActivityAdapter.toSpec(model = model, diagram = diagram)

            spec.edges shouldHaveSize 0
        }

        // ── 6. Edge dropped when target not in diagram ────────────────────────

        test("edge dropped when target not in diagram elementIds") {
            val model =
                sysml2Model(name = "FilterTarget") {
                    val visible = actionDef(name = "Visible")
                    val hidden = actionDef(name = "Hidden")
                    controlFlow(name = "cf1", source = visible, target = hidden)
                    actDiagram(name = "FilterTarget ACT") {
                        include(visible)
                        // hidden NOT included
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            val spec = Sysml2ActivityAdapter.toSpec(model = model, diagram = diagram)

            spec.edges shouldHaveSize 0
        }

        // ── 7. Full order-processing fixture produces correct node + edge count ─

        test("full order-processing example produces correct node and edge counts") {
            val model =
                sysml2Model(name = "OrderProcessing") {
                    val init = initialNode()
                    val place = actionDef(name = "PlaceOrder", action = "submit(order)")
                    val validate = actionDef(name = "ValidateOrder", action = "validate(order)")
                    val decide = decisionNode(name = "valid?")
                    val pay = actionDef(name = "ProcessPayment", action = "charge(total)")
                    val cancel = actionDef(name = "CancelOrder", action = "notify(cancelled)")
                    val fin = finalNode()
                    val ff = flowFinalNode()

                    controlFlow(name = "start", source = init, target = place)
                    controlFlow(name = "placed", source = place, target = validate)
                    controlFlow(name = "validated", source = validate, target = decide)
                    controlFlow(name = "yes", source = decide, target = pay, guard = "valid")
                    controlFlow(name = "no", source = decide, target = cancel, guard = "!valid")
                    controlFlow(name = "payEnd", source = pay, target = fin)
                    controlFlow(name = "cancelEnd", source = cancel, target = ff)

                    actDiagram(name = "Order ACT") {
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

            val spec = Sysml2ActivityAdapter.toSpec(model = model, diagram = diagram)

            spec.nodes.size shouldBe 8
            spec.edges shouldHaveSize 7
        }

        // ── 8. Multi-initial model ─────────────────────────────────────────────

        test("multi-initial model has two Initial nodes in spec") {
            val model =
                sysml2Model(name = "MultiInitial") {
                    // Use unique IDs to prevent id-collision in associateBy
                    val i1 = initialNode(id = "Init1")
                    val i2 = initialNode(id = "Init2")
                    val a = actionDef(name = "A")
                    val b = actionDef(name = "B")
                    val fin = finalNode()
                    controlFlow(name = "c1", source = i1, target = a)
                    controlFlow(name = "c2", source = i2, target = b)
                    controlFlow(name = "c3", source = a, target = fin)
                    controlFlow(name = "c4", source = b, target = fin)
                    actDiagram(name = "Multi ACT") {
                        include(i1)
                        include(i2)
                        include(a)
                        include(b)
                        include(fin)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            val spec = Sysml2ActivityAdapter.toSpec(model = model, diagram = diagram)

            val initialNodes = spec.nodes.values.filter { it.kind == ActivityNodeKind.Initial }
            initialNodes shouldHaveSize 2
        }

        // ── V2.0.20b: ACT guard pre-parse tests ──────────────────────────────

        test("adapter pre-parses ControlFlow guards — parseable guard does not throw") {
            val model =
                sysml2Model(name = "PreParseGuard") {
                    val init = initialNode()
                    val act = actionDef(name = "A")
                    val fin = finalNode()
                    // "valid" is a simple IDENT — parseable by OclLikeExpressionParser
                    controlFlow(name = "c1", source = init, target = act, guard = "valid")
                    controlFlow(name = "c2", source = act, target = fin)
                    actDiagram(name = "PreParse ACT") {
                        include(init)
                        include(act)
                        include(fin)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            // runtimeFor must not throw even when guards are pre-parsed
            val runtime = Sysml2ActivityAdapter.runtimeFor(model = model, diagram = diagram)
            // Spec is correctly built with the guard on the edge
            val guardedEdge = runtime.spec.edges.first { it.guard != null }
            guardedEdge.guard shouldBe "valid"
        }

        test("adapter tolerates unparseable guard at construction — does not throw") {
            val model =
                sysml2Model(name = "UnparseableGuard") {
                    val init = initialNode()
                    val act = actionDef(name = "A")
                    val fin = finalNode()
                    // "@@@" cannot be parsed — should not throw at adapter construction time
                    controlFlow(name = "c1", source = init, target = act, guard = "@@@")
                    controlFlow(name = "c2", source = act, target = fin)
                    actDiagram(name = "Unparseable ACT") {
                        include(init)
                        include(act)
                        include(fin)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            // Must NOT throw — unparseable guards are silently ignored at construction
            val runtime = Sysml2ActivityAdapter.runtimeFor(model = model, diagram = diagram)
            val guardedEdge = runtime.spec.edges.first { it.guard != null }
            guardedEdge.guard shouldBe "@@@"
        }

        // ── bonus: full run-to-termination via runtimeFor ─────────────────────

        test("runtimeFor produces a runtime that runs order-processing to termination") {
            val model =
                sysml2Model(name = "RunTest") {
                    val init = initialNode()
                    val act = actionDef(name = "Work", action = "work()")
                    val fin = finalNode()
                    controlFlow(name = "c1", source = init, target = act)
                    controlFlow(name = "c2", source = act, target = fin)
                    actDiagram(name = "Run ACT") {
                        include(init)
                        include(act)
                        include(fin)
                    }
                }
            val diagram = model.diagrams.first() as dev.kuml.sysml2.ActDiagram

            val runtime = Sysml2ActivityAdapter.runtimeFor(model = model, diagram = diagram)
            val (instance, trace) = runtime.run()

            instance.isTerminated shouldBe true
            trace.filterIsInstance<TraceEntry.ActivityTerminated>() shouldHaveSize 1
            trace
                .filterIsInstance<TraceEntry.ActivityActionInvoked>()
                .any { it.body == "work()" } shouldBe true
        }
    })
