---
type: UmlClassDiagram
title: Ticket-Datenmodell
---

# Ticket-Datenmodell (Muster-Firma, fiktives Beispiel)

> Teil des frei erfundenen Beispiel-Workspace „Support-Ticket-Prozess der
> Muster-Firma GmbH" — siehe [Workspace-Index](../index.md).

UML-Klassendiagramm der zentralen Entitäten aus
[§1 SLA-Definition](../articles/01-sla-definition.md): ein
[Kunde](../concepts/Kunde.md) eröffnet [Tickets](../concepts/Ticket.md),
die von einem [Supportmitarbeiter](../concepts/Supportmitarbeiter.md)
bearbeitet werden.

```kuml
classDiagram(name = "Ticket-Datenmodell") {
    showOperations = false

    val kunde = classOf(name = "Kunde") {
        attribute(name = "supportLevel", type = "String")
    }
    val ticket = classOf(name = "Ticket") {
        attribute(name = "titel", type = "String")
        attribute(name = "erstelltAm", type = "Date")
        attribute(name = "prioritaet", type = "String")
    }
    val mitarbeiter = classOf(name = "Supportmitarbeiter") {
        attribute(name = "istSenior", type = "Boolean")
    }

    association(source = kunde, target = ticket) {
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "0..*"); role = "tickets" }
    }
    association(source = mitarbeiter, target = ticket) {
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "0..*"); role = "bearbeitet" }
    }
}
```
