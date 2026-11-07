import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

bpmnModel(name = "Bestellprozess Choreographie") {
    val chId = choreography(id = "order_choreo", name = "Bestellung") {
        val start = startEvent(name = "Bedarf erkannt")

        val bestellung = task(
            name = "Bestellung aufgeben",
            initiatingParticipant = "Kunde",
            participants = arrayOf("Kunde", "Händler"),
        ) {
            message(name = "Bestellanfrage", participantRef = "Kunde", isInitiating = true)
            message(name = "Auftragsbestätigung", participantRef = "Händler", isInitiating = false)
        }

        val gw = gateway(type = GatewayType.EXCLUSIVE, name = "Auf Lager?")

        val nachbestellung = task(
            name = "Nachbestellen",
            initiatingParticipant = "Händler",
            participants = arrayOf("Händler", "Lieferant"),
        ) {
            message(name = "Nachbestellung", participantRef = "Händler", isInitiating = true)
            message(name = "Lieferzusage", participantRef = "Lieferant", isInitiating = false)
        }

        val lieferung = task(
            name = "Lieferung",
            initiatingParticipant = "Händler",
            participants = arrayOf("Händler", "Kunde"),
        ) {
            message(name = "Versandbenachrichtigung", participantRef = "Händler", isInitiating = true)
        }

        val end = endEvent(name = "Bestellung abgeschlossen")

        sequenceFlow(from = start, to = bestellung)
        sequenceFlow(from = bestellung, to = gw)
        sequenceFlow(from = gw, to = lieferung, condition = "auf Lager", name = "Ja")
        sequenceFlow(from = gw, to = nachbestellung, condition = "nicht auf Lager", name = "Nein")
        sequenceFlow(from = nachbestellung, to = lieferung)
        sequenceFlow(from = lieferung, to = end)
    }

    choreographyDiagram("Bestellprozess-Choreographie", choreographyId = chId)
}
