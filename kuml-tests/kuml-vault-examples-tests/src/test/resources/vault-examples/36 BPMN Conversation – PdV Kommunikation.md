---
tags: [kUML, BPMN, beispiel]
status: aktiv
date: 2026-06-30
---

# BPMN Conversation – PdV Kommunikation

Vereinfachte Interaktionssicht: Welche Participants kommunizieren miteinander, ohne interne Prozesslogik. Das Conversation Diagram zeigt Hexagon-Knoten (Konversationen) und Rechteck-Knoten (Participants), verbunden durch ungerichtete Conversation Links.

> [!note] Conversation Diagram vs. Collaboration
> Ein Conversation Diagram ist die "Vogelperspektive" auf Kommunikationsbeziehungen — es zeigt **wer mit wem** kommuniziert (Hexagons = Themen/Konversationen, Rechtecke = Participants), aber **nicht wie** (keine Prozesslogik, keine Message Flows). Für interne Prozessdetails wird ein [[32 BPMN Collaboration – Customer und Supplier|Collaboration Diagram]] verwendet.

> [!tip] DSL-Schreibweise
> - `participant("Name")` deklariert einen Teilnehmer und gibt den Namen als ID zurück.
> - `node("Name", p1, p2)` erstellt einen Konversationsknoten (Hexagon) mit mindestens 2 Participants.
> - `callConversation(...)` für externe Kollaborationen (dicker Rand), `subConversation(...)` für verschachtelte Konversationen (+-Marker).
> - `link(participantRef, nodeRef)` verbindet Participant mit Konversationsknoten (ungerichtet, kein Pfeilkopf).

```kuml
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
```
