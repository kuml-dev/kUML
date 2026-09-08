@file:Suppress("unused")

import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

/**
 * BPMN OKF vault example fixture (ADR-0015 CLI tests) — copied verbatim from
 * `03 Bereiche/kUML/Beispiele` → `sample-support-ticket-process/models/eskalationsprozess.md`.
 */
bpmnModel(name = "Eskalationsprozess") {
    process(id = "escalation", name = "Eskalationsprozess") {
        val start = startEvent(name = "Frist ueberschritten")
        val pruefen = task(name = "Verfuegbaren Senior pruefen", type = TaskType.SERVICE)
        val verfuegbarGw = gateway(type = GatewayType.EXCLUSIVE, name = "Senior verfuegbar?")
        val uebernehmen = task(name = "Ticket uebernehmen", type = TaskType.USER)
        val warten = task(name = "In Warteschlange stellen", type = TaskType.SEND)
        val benachrichtigen = task(name = "Kunde benachrichtigen", type = TaskType.SEND)
        val end = endEvent(name = "Eskalation eingeleitet")

        sequenceFlow(from = start, to = pruefen)
        sequenceFlow(from = pruefen, to = verfuegbarGw)
        sequenceFlow(from = verfuegbarGw, to = uebernehmen, condition = "verfuegbar", name = "Ja")
        sequenceFlow(from = verfuegbarGw, to = warten, condition = "nichtVerfuegbar", name = "Nein", default = true)
        sequenceFlow(from = uebernehmen, to = benachrichtigen)
        sequenceFlow(from = warten, to = benachrichtigen)
        sequenceFlow(from = benachrichtigen, to = end)
    }
    diagram(name = "Eskalationsprozess", processId = "escalation")
}
