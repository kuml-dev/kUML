---
type: BpmnCollaboration
title: Zusammenarbeit
---

# Zusammenarbeit

Dieses BPMN-Diagramm ist Teil des LaTeX-Exports: Pools, Lanes, Prozessknoten und Nachrichtenflüsse.

```kuml
import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

bpmnModel(name = "Customer-Supplier Collaboration") {
    process(id = "p_customer", name = "Customer") {
        val placeOrder = startEvent(name = "Place Order", definition = EventDefinition.MESSAGE)
        val waitShip   = task(name = "Wait for Shipment", type = TaskType.RECEIVE)
        val receive    = task(name = "Receive Goods", type = TaskType.USER)
        val endOk      = endEvent(name = "Order Complete")
        sequenceFlow(from = placeOrder, to = waitShip)
        sequenceFlow(from = waitShip, to = receive)
        sequenceFlow(from = receive, to = endOk)
    }
    process(id = "p_supplier", name = "Supplier") {
        val receiveOrder = startEvent(name = "Receive Order", definition = EventDefinition.MESSAGE)
        val pick         = task(name = "Pick & Pack", type = TaskType.MANUAL)
        val ship         = task(name = "Ship Goods", type = TaskType.SERVICE)
        val endShipped   = endEvent(name = "Goods Shipped", definition = EventDefinition.MESSAGE)
        sequenceFlow(from = receiveOrder, to = pick)
        sequenceFlow(from = pick, to = ship)
        sequenceFlow(from = ship, to = endShipped)
    }
    collaboration(name = "Order Flow", id = "collab_1") {
        pool(name = "Customer", id = "pool_customer") { process(processId = "p_customer") }

        // Lieferanten-Pool mit zwei Lanes: Order-Handling liegt bei "Einkauf", Versand bei "Logistik"
        pool(name = "Supplier", id = "pool_supplier") {
            process(processId = "p_supplier")
            lane(name = "Einkauf") { contains("p_supplier_start_1", "p_supplier_task_2") }
            lane(name = "Logistik") { contains("p_supplier_task_3", "p_supplier_end_4") }
        }

        // Externe Spedition — kein einsehbarer interner Prozess
        val carrier = blackBoxPool(name = "Spedition", id = "pool_carrier")

        messageFlow(from = "p_customer_start_1", to = "p_supplier_start_1", name = "Purchase Order")
        messageFlow(from = "p_supplier_end_4", to = "p_customer_task_2", name = "Shipping Notification")
        messageFlow(from = "p_supplier_task_3", to = carrier, name = "Pickup Request")
    }
    collaborationDiagram(name = "Customer-Supplier", collaborationId = "collab_1")
}
```
