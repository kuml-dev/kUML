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

bpmnModel("Customer-Supplier Collaboration") {
    process(id = "p_customer", name = "Customer") {
        val placeOrder = startEvent("Place Order", EventDefinition.MESSAGE)
        val waitShip   = task("Wait for Shipment", TaskType.RECEIVE)
        val receive    = task("Receive Goods", TaskType.USER)
        val endOk      = endEvent("Order Complete")
        sequenceFlow(placeOrder, waitShip)
        sequenceFlow(waitShip, receive)
        sequenceFlow(receive, endOk)
    }
    process(id = "p_supplier", name = "Supplier") {
        val receiveOrder = startEvent("Receive Order", EventDefinition.MESSAGE)
        val pick         = task("Pick & Pack", TaskType.MANUAL)
        val ship         = task("Ship Goods", TaskType.SERVICE)
        val endShipped   = endEvent("Goods Shipped", EventDefinition.MESSAGE)
        sequenceFlow(receiveOrder, pick)
        sequenceFlow(pick, ship)
        sequenceFlow(ship, endShipped)
    }
    collaboration(name = "Order Flow", id = "collab_1") {
        pool(name = "Customer", id = "pool_customer") { process("p_customer") }
        pool(name = "Supplier", id = "pool_supplier") { process("p_supplier") }
        messageFlow(from = "p_customer_start_1", to = "p_supplier_start_1", name = "Purchase Order")
        messageFlow(from = "p_supplier_end_1", to = "p_customer_task_1", name = "Shipping Notification")
    }
    collaborationDiagram("Customer-Supplier", "collab_1")
}
```
