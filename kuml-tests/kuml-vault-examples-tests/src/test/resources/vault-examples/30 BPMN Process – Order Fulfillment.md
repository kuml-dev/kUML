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

bpmnModel(name = "Order Fulfillment") {
    process(id = "p_order", name = "Order Process") {
        val start   = startEvent(name = "Order Received", definition = EventDefinition.MESSAGE)
        val check   = task(name = "Check Stock", type = TaskType.SERVICE)
        val gw      = gateway(type = GatewayType.EXCLUSIVE, name = "In Stock?")
        val ship    = task(name = "Ship Order", type = TaskType.USER)
        val notify  = task(name = "Notify Customer", type = TaskType.SEND)
        val reject  = task(name = "Reject Order", type = TaskType.SEND)
        val endOk   = endEvent(name = "Order Shipped")
        val endFail = endEvent(name = "Order Rejected")

        sequenceFlow(from = start, to = check)
        sequenceFlow(from = check, to = gw)
        sequenceFlow(from = gw, to = ship, condition = "stock > 0", name = "Yes")
        sequenceFlow(from = gw, to = reject, condition = "stock == 0", name = "No")
        sequenceFlow(from = ship, to = notify)
        sequenceFlow(from = notify, to = endOk)
        sequenceFlow(from = reject, to = endFail)
    }
    diagram(name = "Order Fulfillment", processId = "p_order")
}
```
