package dev.kuml.transform.bpmnuml

import dev.kuml.codegen.m2m.TransformerRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class BpmnUmlBridgeRegistryTest :
    FunSpec({

        beforeTest {
            TransformerRegistry.clear()
        }

        afterTest {
            TransformerRegistry.clear()
        }

        test("registerAll registers both transformer ids") {
            BpmnUmlBridgeRegistry.registerAll()
            val ids = TransformerRegistry.ids()
            ids shouldContain "bpmn-to-uml-activity"
            ids shouldContain "uml-activity-to-bpmn"
        }

        test("loadFromClasspath discovers both providers via ServiceLoader") {
            TransformerRegistry.loadFromClasspath()
            val ids = TransformerRegistry.ids()
            ids shouldContain "bpmn-to-uml-activity"
            ids shouldContain "uml-activity-to-bpmn"
        }

        test("registerAll is idempotent") {
            BpmnUmlBridgeRegistry.registerAll()
            BpmnUmlBridgeRegistry.registerAll()
            // Should still have exactly these two ids (plus any others loaded)
            val ids = TransformerRegistry.ids()
            ids shouldContain "bpmn-to-uml-activity"
            ids shouldContain "uml-activity-to-bpmn"
        }

        test("BpmnToUmlActivityTransformer has correct id") {
            BpmnToUmlActivityTransformer().id shouldBe "bpmn-to-uml-activity"
        }

        test("UmlActivityToBpmnTransformer has correct id") {
            UmlActivityToBpmnTransformer().id shouldBe "uml-activity-to-bpmn"
        }
    })
