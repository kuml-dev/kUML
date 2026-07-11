---
type: UmlStateMachine
title: Mitgliedschafts-Lebenszyklus
---

# Mitgliedschafts-Lebenszyklus (Muster-Satzung, fiktives Beispiel)

> Teil des frei erfundenen Beispiel-Workspace „Muster-Verein für Offene
> Zusammenarbeit e.V." — siehe [Workspace-Index](../index.md).

UML-Zustandsdiagramm des Lebenszyklus eines [Mitglieds](../concepts/Mitglied.md)
gemäß [§2 Mitgliedschaft](../articles/02-mitgliedschaft.md): von der Bewerbung
über die Aufnahme (siehe [Aufnahmeverfahren](aufnahme-prozess.md)), aktive und
ruhende Phasen bis zum Austritt oder Ausschluss.

```kuml
stateDiagram(name = "Mitgliedschafts-Lebenszyklus") {
    val start = initialState()
    val beworben = state(name = "Beworben")
    val aktiv = state(name = "Aktiv")
    val ruhend = state(name = "Ruhend")
    val ausgetreten = finalState(name = "Ausgetreten")
    val ausgeschlossen = finalState(name = "Ausgeschlossen")

    transition(source = start, target = beworben)
    transition(source = beworben, target = aktiv) {
        trigger = "aufnehmen()"
        guard = "[Vorstand genehmigt]"
    }
    transition(source = aktiv, target = ruhend) {
        trigger = "beitragRueckstand()"
    }
    transition(source = ruhend, target = aktiv) {
        trigger = "beitragBezahlt()"
    }
    transition(source = aktiv, target = ausgetreten) {
        trigger = "austreten()"
    }
    transition(source = aktiv, target = ausgeschlossen) {
        trigger = "ausschliessen()"
        guard = "[Mitgliederversammlung-Beschluss]"
    }
    transition(source = ruhend, target = ausgeschlossen) {
        trigger = "ausschliessen()"
        guard = "[Mitgliederversammlung-Beschluss]"
    }
}
```
