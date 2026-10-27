package dev.kuml.bpmn

import dev.kuml.bpmn.constraint.BpmnNoteChecker
import dev.kuml.bpmn.dsl.bpmnModel
import dev.kuml.bpmn.model.BpmnModel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json

class BpmnTextAnnotationDslTest :
    FunSpec(body = {

        test("textAnnotation and note, anchors, deterministic ids") {
            val model =
                bpmnModel(name = "M") {
                    process(id = "p") {
                        val s = startEvent()
                        val t = task(name = "T")
                        s flowsTo t
                        textAnnotation(text = "about the task", t)
                        note(text = "free")
                    }
                }
            val p = model.processes.single()
            p.textAnnotations.map { it.id } shouldContainExactly listOf("p_annotation_1", "p_annotation_2")
            p.associations
                .single()
                .link.noteId shouldBe "p_annotation_1"
            p.associations
                .single()
                .link.targetId shouldBe "p_task_2"
            p.associations.single().id shouldBe "p_association_1_1"
        }

        test("annotations in sub-processes are hoisted into the enclosing process (expanded and collapsed)") {
            for (expanded in listOf(true, false)) {
                val model =
                    bpmnModel(name = "M") {
                        process(id = "p") {
                            subProcess(name = "S", expanded = expanded) {
                                val inner = task(name = "In")
                                textAnnotation(text = "inner note", inner)
                            }
                        }
                    }
                val p = model.processes.single()
                p.textAnnotations shouldHaveSize 1
                p.associations shouldHaveSize 1
            }
        }

        test("anchoring a lane, a pool or an unknown id throws and names both ids") {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    bpmnModel(name = "M") {
                        process(id = "p") { textAnnotation(text = "x", "nope", id = "ann") }
                    }
                }
            ex.message!! shouldContain "ann"
            ex.message!! shouldContain "nope"
            shouldThrow<IllegalArgumentException> {
                bpmnModel(name = "M") {
                    process(id = "p") { task(name = "T") }
                    collaboration(id = "c") {
                        pool(name = "A", id = "poolA") { process("p") }
                        textAnnotation(text = "x", "poolA")
                    }
                }
            }
        }

        test("collaboration level annotation anchors to a flow node of a referenced process") {
            val model =
                bpmnModel(name = "M") {
                    process(id = "p") { task(name = "T") }
                    collaboration(id = "c") {
                        pool(name = "A", id = "poolA") { process("p") }
                        textAnnotation(text = "on collab", "p_task_1")
                    }
                }
            model.collaborations.single().associations shouldHaveSize 1
            BpmnNoteChecker.check(model) shouldBe emptyList()
        }

        test("json round trip and old json compatibility") {
            val model =
                bpmnModel(name = "M") {
                    process(id = "p") {
                        val t = task(name = "T")
                        textAnnotation(text = "a \"quoted\" <b>", t)
                    }
                }
            val json = Json { ignoreUnknownKeys = true }
            val text = json.encodeToString(BpmnModel.serializer(), model)
            json.decodeFromString(BpmnModel.serializer(), text) shouldBe model
            val old = """{"name":"O","processes":[{"id":"p"}]}"""
            json
                .decodeFromString(BpmnModel.serializer(), old)
                .processes
                .single()
                .textAnnotations shouldBe emptyList()
        }

        test("checker reports unknown refs (error) and unsupported targets (warning)") {
            val base =
                bpmnModel(name = "M") {
                    process(id = "p") {
                        val t = task(name = "T")
                        startEvent() flowsTo t
                        textAnnotation(text = "x", t, id = "ann")
                    }
                }
            val p = base.processes.single()
            val bad =
                base.copy(
                    processes =
                        listOf(
                            p.copy(
                                associations =
                                    p.associations +
                                        dev.kuml.bpmn.model.BpmnAssociation(
                                            link =
                                                dev.kuml.core.model.note
                                                    .KumlNoteLink(id = "a1", noteId = "ann", targetId = "ghost"),
                                        ) +
                                        dev.kuml.bpmn.model.BpmnAssociation(
                                            link =
                                                dev.kuml.core.model.note
                                                    .KumlNoteLink(id = "a2", noteId = "ann", targetId = "p_task_1")
                                                    .copy(targetId = "p_flow_1"),
                                        ),
                            ),
                        ),
                )
            val codes = BpmnNoteChecker.check(bad).map { it.code }
            codes shouldContainExactly listOf(BpmnNoteChecker.UNKNOWN_REF, BpmnNoteChecker.UNSUPPORTED_TARGET)
        }

        test("association to a node of an expanded sub-process nested in a collapsed one is re-targeted") {
            val model =
                bpmnModel(name = "M") {
                    process(id = "p") {
                        subProcess(expanded = false) {
                            subProcess(expanded = true) {
                                val t = task(name = "T")
                                val d = dataObject(name = "D")
                                textAnnotation(text = "n", t)
                                textAnnotation(text = "m", d)
                            }
                        }
                    }
                }
            val p = model.processes.single()
            p.associations.map { it.link.targetId }.distinct() shouldContainExactly listOf("p_sub_1")
        }

        test("a text annotation id colliding with a flow node id is rejected") {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    bpmnModel(name = "M") {
                        process(id = "p") {
                            val t = task(name = "T")
                            textAnnotation(text = "x", t, id = t)
                        }
                    }
                }
            ex.message shouldContain "collides"
        }

        test("a text annotation id colliding with a sequence flow id is rejected") {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    bpmnModel(name = "M") {
                        process(id = "p") {
                            val a = task(name = "A")
                            val b = task(name = "B")
                            a flowsTo b
                            textAnnotation(text = "x", a, id = "p_flow_1")
                        }
                    }
                }
            ex.message shouldContain "collides"
        }
    })
