import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

bpmnModel(name = "PdV Kommunikation") {
    val convId = conversation(id = "pdv_conv", name = "PdV-Interaktionen") {
        val mitglied  = participant("Mitglied")
        val vorstand  = participant("Vorstand")
        val netzwerk  = participant("Netzwerk")
        val oeffentlich = participant("Öffentlichkeit")

        // Konversationsknoten (Hexagons)
        val beitritt  = node("Beitrittsantrag",   mitglied, vorstand)
        val kampagne  = node("Wahlkampagne",       vorstand, netzwerk)
        val presse    = node("Pressekommunikation", vorstand, oeffentlich)
        val vernetzung = node("Mitglieder-Vernetzung", mitglied, netzwerk)

        // Conversation Links (ungerichtet)
        link(mitglied,    beitritt)
        link(vorstand,    beitritt)
        link(vorstand,    kampagne)
        link(netzwerk,    kampagne)
        link(vorstand,    presse)
        link(oeffentlich, presse)
        link(mitglied,    vernetzung)
        link(netzwerk,    vernetzung)
    }

    conversationDiagram("PdV-Kommunikationsübersicht", conversationId = convId)
}
