---
tags: [kUML, BPMN, beispiel]
status: aktiv
date: 2026-06-22
---

# BPMN Process – Order Fulfillment

Einfacher Bestellprozess: von der Bestellung bis zur Lieferung oder Ablehnung.

```kuml
import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

bpmnModel("Order Fulfillment") {
    process(id = "p_order", name = "Order Process") {
        val start   = startEvent("Order Received", EventDefinition.MESSAGE)
        val check   = task("Check Stock", TaskType.SERVICE)
        val gw      = gateway(GatewayType.EXCLUSIVE, "In Stock?")
        val ship    = task("Ship Order", TaskType.USER)
        val notify  = task("Notify Customer", TaskType.SEND)
        val reject  = task("Reject Order", TaskType.SEND)
        val endOk   = endEvent("Order Shipped")
        val endFail = endEvent("Order Rejected")

        sequenceFlow(start, check)
        sequenceFlow(check, gw)
        sequenceFlow(gw, ship, condition = "stock > 0", name = "Yes")
        sequenceFlow(gw, reject, condition = "stock == 0", name = "No")
        sequenceFlow(ship, notify)
        sequenceFlow(notify, endOk)
        sequenceFlow(reject, endFail)
    }
    diagram("Order Fulfillment", "p_order")
}
```
