---
tags: [kUML, BPMN, beispiel]
status: aktiv
date: 2026-06-22
---

# BPMN Collaboration – Customer und Supplier

Zwei-Pool-Collaboration: Kunde bestellt, Lieferant liefert. MessageFlows zwischen den Pools.

> [!note] Pool-Prozesse und MessageFlow-IDs
> Ein Pool referenziert seinen internen Prozess über `process("…")` im Pool-Block (keine Ownership). `messageFlow(from, to, name)` verbindet Elemente **verschiedener** Pools über deren Auto-IDs. Die Auto-IDs folgen dem Schema `{processId}_{typ}_{zähler}` — das erste Start-Event von `p_customer` ist `p_customer_start_1`, der erste Task `p_customer_task_1`.

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
        pool(name = "Supplier", id = "pool_supplier") { process(processId = "p_supplier") }
        messageFlow(from = "p_customer_start_1", to = "p_supplier_start_1", name = "Purchase Order")
        messageFlow(from = "p_supplier_end_1", to = "p_customer_task_1", name = "Shipping Notification")
    }
    collaborationDiagram(name = "Customer-Supplier", collaborationId = "collab_1")
}
```
