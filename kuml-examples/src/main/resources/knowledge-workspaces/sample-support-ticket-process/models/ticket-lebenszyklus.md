---
type: UmlStateMachine
title: Ticket-Lebenszyklus
---

# Ticket-Lebenszyklus (Muster-Firma, fiktives Beispiel)

> Teil des frei erfundenen Beispiel-Workspace „Support-Ticket-Prozess der
> Muster-Firma GmbH" — siehe [Workspace-Index](../index.md).

UML-Zustandsdiagramm des Lebenszyklus eines [Tickets](../concepts/Ticket.md)
gemäß [§1 SLA-Definition](../articles/01-sla-definition.md) und
[§2 Eskalationsregeln](../articles/02-eskalationsregeln.md): von der
Eröffnung über die Bearbeitung, eine mögliche automatische Eskalation bis
zum Abschluss.

Dieses Diagramm eignet sich, um die State-Machine-Simulation in kUML
Desktop (vierter Editor-Modus, Ctrl+4) direkt an einem Wissens-Workspace
auszuprobieren — z. B. den Pfad `eroeffnet()` → `zuweisen()` →
`fristUeberschritten()` → `uebernehmen()` → `loesen()` → `schliessen()`
durchklicken.

```kuml
stateDiagram(name = "Ticket-Lebenszyklus") {
    val start = initialState()
    val offen = state(name = "Offen")
    val inBearbeitung = state(name = "InBearbeitung")
    val eskaliert = state(name = "Eskaliert")
    val geloest = state(name = "Geloest")
    val geschlossen = finalState(name = "Geschlossen")

    transition(source = start, target = offen)
    transition(source = offen, target = inBearbeitung) {
        trigger = "zuweisen()"
    }
    transition(source = inBearbeitung, target = eskaliert) {
        trigger = "fristUeberschritten()"
    }
    transition(source = eskaliert, target = inBearbeitung) {
        trigger = "uebernehmen()"
        guard = "[Senior-Supportmitarbeiter verfuegbar]"
    }
    transition(source = inBearbeitung, target = geloest) {
        trigger = "loesen()"
    }
    transition(source = geloest, target = geschlossen) {
        trigger = "schliessen()"
    }
    transition(source = geloest, target = inBearbeitung) {
        trigger = "wiedereroeffnen()"
        guard = "[Kunde widerspricht]"
    }
}
```
