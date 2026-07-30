package dev.kuml.transform.bpmnuml

import dev.kuml.bpmn.dsl.bpmnModel
import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnLane
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.model.KumlMetaValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class BpmnParticipantToUmlActivityTransformerTest :
    FunSpec({

        val transformer = BpmnParticipantToUmlActivityTransformer()
        val ctx = TransformContext()

        test("participant with lanes maps lane membership into uml.partition metadata") {
            var reviewTaskId = ""
            var approveTaskId = ""
            val model =
                bpmnModel(name = "Collab") {
                    process(id = "proc1", name = "Approval Process") {
                        val start = startEvent(name = "Start")
                        val t1 = task(name = "Review").also { reviewTaskId = it }
                        val t2 = task(name = "Approve").also { approveTaskId = it }
                        val end = endEvent(name = "End")
                        sequenceFlow(from = start, to = t1)
                        sequenceFlow(from = t1, to = t2)
                        sequenceFlow(from = t2, to = end)
                    }
                }

            val lane1 = BpmnLane(id = "lane_reviewer", name = "Reviewer", flowNodeRefs = listOf(reviewTaskId))
            val lane2 = BpmnLane(id = "lane_approver", name = "Approver", flowNodeRefs = listOf(approveTaskId))
            val participant =
                BpmnParticipant(id = "p1", name = "Approval Pool", processRef = "proc1", lanes = listOf(lane1, lane2))
            val collaboration = BpmnCollaboration(id = "collab1", participants = listOf(participant))
            val fullModel = BpmnModel(name = "Collab", processes = model.processes, collaborations = listOf(collaboration))

            val bundle =
                BpmnParticipantBundle(
                    participant = participant,
                    process = fullModel.processes.first(),
                )

            val result = transformer.transform(source = bundle, ctx = ctx)
            (result is TransformResult.Success) shouldBe true
            val success = result as TransformResult.Success
            val umlModel = BpmnToUmlActivityMapper.map(process = fullModel.processes.first(), lanes = listOf(lane1, lane2))

            val reviewNode = umlModel.nodes.first { it.id == reviewTaskId }
            val approveNode = umlModel.nodes.first { it.id == approveTaskId }
            (reviewNode.metadata["uml.partition"] as? KumlMetaValue.Text)?.value shouldBe "Reviewer"
            (approveNode.metadata["uml.partition"] as? KumlMetaValue.Text)?.value shouldBe "Approver"

            // Verify the transformer produced a non-empty script
            val content = success.output.first().content
            content.contains("activityDiagram(") shouldBe true
        }

        test("participant with no processRef returns Failure") {
            val participant = BpmnParticipant(id = "blackbox", name = "Black Box Pool", processRef = null)
            val bundle = BpmnParticipantBundle(participant = participant, process = null)
            val result = transformer.transform(source = bundle, ctx = ctx)
            (result is TransformResult.Failure) shouldBe true
        }

        test("BpmnParticipantBundle.from resolves participant and process from BpmnModel") {
            val model =
                bpmnModel(name = "ColRes") {
                    process(id = "procR", name = "Resolved Process") {
                        val start = startEvent(name = "Start")
                        val end = endEvent(name = "End")
                        sequenceFlow(from = start, to = end)
                    }
                }

            val participant = BpmnParticipant(id = "p_res", name = "Resolved Pool", processRef = "procR")
            val collaboration = BpmnCollaboration(id = "c_res", participants = listOf(participant))
            val fullModel = BpmnModel(name = "ColRes", processes = model.processes, collaborations = listOf(collaboration))

            val bundle = BpmnParticipantBundle.from(model = fullModel, participantId = "p_res")
            bundle shouldNotBe null
            bundle!!.participant.id shouldBe "p_res"
            bundle.process shouldNotBe null
            bundle.process!!.id shouldBe "procR"
        }

        test("BpmnParticipantBundle.allFrom returns bundles for all participants") {
            val model =
                bpmnModel(name = "AllBundles") {
                    process(id = "proc_a", name = "Process A") {
                        val start = startEvent(name = "Start A")
                        val end = endEvent(name = "End A")
                        sequenceFlow(from = start, to = end)
                    }
                    process(id = "proc_b", name = "Process B") {
                        val start = startEvent(name = "Start B")
                        val end = endEvent(name = "End B")
                        sequenceFlow(from = start, to = end)
                    }
                }
            val pA = BpmnParticipant(id = "pa", processRef = "proc_a")
            val pB = BpmnParticipant(id = "pb", processRef = "proc_b")
            val collab = BpmnCollaboration(id = "c_all", participants = listOf(pA, pB))
            val fullModel = BpmnModel(name = "AllBundles", processes = model.processes, collaborations = listOf(collab))

            val bundles = BpmnParticipantBundle.allFrom(fullModel)
            bundles.size shouldBe 2
            bundles.all { it.process != null } shouldBe true
        }

        test("lane membership is recorded in generated script as comment") {
            var taskId = ""
            val model =
                bpmnModel(name = "CommentCheck") {
                    process(id = "proc_c", name = "Comment Check") {
                        val start = startEvent(name = "Start")
                        val t = task(name = "Do Work").also { taskId = it }
                        val end = endEvent(name = "End")
                        sequenceFlow(from = start, to = t)
                        sequenceFlow(from = t, to = end)
                    }
                }
            val lane = BpmnLane(id = "l1", name = "Team Alpha", flowNodeRefs = listOf(taskId))
            val participant = BpmnParticipant(id = "pp1", name = "Alpha Pool", processRef = "proc_c", lanes = listOf(lane))
            val bundle = BpmnParticipantBundle(participant = participant, process = model.processes.first())

            val result = transformer.transform(source = bundle, ctx = ctx)
            (result is TransformResult.Success) shouldBe true
            val content = (result as TransformResult.Success).output.first().content
            // The script renderer emits lane names as comments
            content.contains("Team Alpha") shouldBe true
        }
    })
