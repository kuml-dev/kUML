import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

bpmnModel(name = "Order Fulfillment") {
    // Model-weiter Datenspeicher — von mehreren Prozessen referenzierbar
    val inventoryLedger = dataStore(name = "Inventory Ledger", unlimited = true)

    process(id = "p_order", name = "Order Process") {
        val start   = startEvent(name = "Order Received", definition = EventDefinition.MESSAGE)
        val check   = task(name = "Check Stock", type = TaskType.SERVICE)
        val orderData = dataObject(name = "Order")
        dataAssociation(from = check, to = orderData)

        val gw      = gateway(type = GatewayType.EXCLUSIVE, name = "In Stock?")
        val ship    = task(name = "Ship Order", type = TaskType.USER)
        val notify  = task(name = "Notify Customer", type = TaskType.SEND)

        // Wiederholungs-Aufgabe: Nachbestellung wird wiederholt versucht (Loop-Marker ↻)
        val reorder = task(name = "Reorder Stock", type = TaskType.SERVICE) {
            standardLoop(testBefore = true)
        }
        val reject  = task(name = "Reject Order", type = TaskType.SEND)

        // Aufruf eines global definierten Zahlungs-Prozesses
        val payment = callActivity(name = "Process Payment", calledElement = "p_payment")

        // Ereignisbasiertes Gateway: race zwischen Zahlungsbestätigung und Timeout —
        // genau einer der beiden nachfolgenden Catch-Events "gewinnt".
        val paymentRace   = gateway(type = GatewayType.EVENT_BASED, name = "Payment outcome?")
        val paymentOk     = intermediateEvent(name = "Payment Confirmed", definition = EventDefinition.MESSAGE)
        val paymentTimeout = intermediateEvent(name = "Payment Timeout", definition = EventDefinition.TIMER)

        // Paralleles Gateway: Versand und Lagerbuchung laufen gleichzeitig
        val fork = gateway(type = GatewayType.PARALLEL, name = "fork")
        val updateInventory = task(name = "Update Inventory", type = TaskType.SERVICE)
        dataAssociation(from = updateInventory, to = inventoryLedger)

        // Komplexes Gateway: Merge mit freitextlicher Zusammenführungsbedingung
        val join = gateway(type = GatewayType.COMPLEX, name = "Shipment + Inventory ready")

        // Inklusives Gateway: sowohl Kunden- als auch interne Benachrichtigung möglich
        val notifyGw = gateway(type = GatewayType.INCLUSIVE, name = "Notify channels")

        val endOk   = endEvent(name = "Order Shipped")
        val endFail = endEvent(name = "Order Rejected")

        sequenceFlow(from = start, to = check)
        sequenceFlow(from = check, to = gw)
        sequenceFlow(from = gw, to = payment, condition = "stock > 0", name = "Yes")
        sequenceFlow(from = gw, to = reorder, condition = "stock == 0", name = "No", default = true)
        sequenceFlow(from = reorder, to = reject)
        sequenceFlow(from = payment, to = paymentRace)
        sequenceFlow(from = paymentRace, to = paymentOk)
        sequenceFlow(from = paymentRace, to = paymentTimeout)
        sequenceFlow(from = paymentOk, to = fork)
        sequenceFlow(from = fork, to = ship)
        sequenceFlow(from = fork, to = updateInventory)
        sequenceFlow(from = ship, to = join)
        sequenceFlow(from = updateInventory, to = join)
        sequenceFlow(from = join, to = notifyGw)
        sequenceFlow(from = notifyGw, to = notify)
        sequenceFlow(from = notify, to = endOk)
        sequenceFlow(from = paymentTimeout, to = reject)
        sequenceFlow(from = reject, to = endFail)
    }
    diagram(name = "Order Fulfillment", processId = "p_order")
}
