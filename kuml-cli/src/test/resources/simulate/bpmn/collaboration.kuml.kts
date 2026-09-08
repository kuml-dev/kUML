@file:Suppress("unused")

import dev.kuml.bpmn.dsl.*

/** A pure BPMN Collaboration diagram (no process at all) — must be rejected by `kuml simulate` (ADR-0015). */
bpmnModel(name = "Collab") {
    collaboration(name = "Collab", id = "collab1") {
        blackBoxPool(name = "Buyer", id = "buyer")
        blackBoxPool(name = "Seller", id = "seller")
    }
    collaborationDiagram(name = "Collab View", collaborationId = "collab1")
}
