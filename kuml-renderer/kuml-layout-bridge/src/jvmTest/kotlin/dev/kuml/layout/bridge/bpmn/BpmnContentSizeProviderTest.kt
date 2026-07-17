package dev.kuml.layout.bridge.bpmn

import dev.kuml.bpmn.model.BpmnCallActivity
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit-Tests für [BpmnContentSizeProvider] — content-aware Breiten-Sizing für
 * Task/SubProcess/CallActivity-Boxen (siehe KDoc der Klasse).
 */
class BpmnContentSizeProviderTest :
    FunSpec({

        val longTask = BpmnTask(id = "long", name = "Schriftlichen Aufnahmeantrag stellen")
        val shortTask = BpmnTask(id = "short", name = "OK")
        val nullNameTask = BpmnTask(id = "null-name", name = null)
        val pathologicalTask = BpmnTask(id = "pathological", name = "X".repeat(500))
        val longSubProcess = BpmnSubProcess(id = "sp-long", name = "Ein sehr langer SubProcess-Name für den Test")
        val longCallActivity = BpmnCallActivity(id = "ca-long", name = "Ein sehr langer CallActivity-Name für den Test")
        val gateway = BpmnGateway(id = "gw1", gatewayType = GatewayType.EXCLUSIVE)
        val event = BpmnEvent(id = "ev1", position = EventPosition.START)

        val process =
            BpmnProcess(
                id = "proc1",
                name = "Test Process",
                flowNodes =
                    listOf(
                        longTask,
                        shortTask,
                        nullNameTask,
                        pathologicalTask,
                        longSubProcess,
                        longCallActivity,
                        gateway,
                        event,
                    ),
            )
        val model = BpmnModel(name = "M", processes = listOf(process))
        val provider = BpmnContentSizeProvider(model)

        test("long task label widens the box beyond the 120 px default") {
            (provider.sizeOf("long", "BpmnTask").width > BpmnLayoutBridge.DEFAULT_TASK_SIZE.width) shouldBe true
        }

        test("short task label keeps the 120 px floor") {
            provider.sizeOf("short", "BpmnTask").width shouldBe 120f
        }

        test("null-named task keeps the 120 px floor") {
            provider.sizeOf("null-name", "BpmnTask").width shouldBe 120f
        }

        test("pathologically long label is capped at MAX_TASK_WIDTH") {
            provider.sizeOf("pathological", "BpmnTask").width shouldBe BpmnContentSizeProvider.MAX_TASK_WIDTH
        }

        test("activity height stays fixed at 60 px regardless of label length") {
            provider.sizeOf("long", "BpmnTask").height shouldBe BpmnLayoutBridge.DEFAULT_TASK_SIZE.height
            provider.sizeOf("pathological", "BpmnTask").height shouldBe BpmnLayoutBridge.DEFAULT_TASK_SIZE.height
        }

        test("collapsed SubProcess with a long name also widens") {
            (provider.sizeOf("sp-long", "BpmnSubProcess").width > BpmnLayoutBridge.DEFAULT_TASK_SIZE.width) shouldBe true
        }

        test("CallActivity with a long name also widens") {
            (provider.sizeOf("ca-long", "BpmnCallActivity").width > BpmnLayoutBridge.DEFAULT_TASK_SIZE.width) shouldBe true
        }

        test("gateway keeps the untouched default size — label renders outside the shape") {
            provider.sizeOf("gw1", "BpmnGateway") shouldBe BpmnLayoutBridge.DEFAULT_GATEWAY_SIZE
        }

        test("event keeps the untouched default size — label renders outside the shape") {
            provider.sizeOf("ev1", "BpmnEvent") shouldBe BpmnLayoutBridge.DEFAULT_EVENT_SIZE
        }

        test("unknown id + unknown kind falls back to the default task size") {
            provider.sizeOf("does-not-exist", "SomethingElse") shouldBe BpmnLayoutBridge.DEFAULT_TASK_SIZE
        }
    })
