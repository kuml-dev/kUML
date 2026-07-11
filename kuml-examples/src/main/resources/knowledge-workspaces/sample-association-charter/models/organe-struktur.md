---
type: UmlClassDiagram
title: Organe-Struktur
---

# Organe-Struktur (Muster-Satzung, fiktives Beispiel)

> Teil des frei erfundenen Beispiel-Workspace „Muster-Verein für Offene
> Zusammenarbeit e.V." — siehe [Workspace-Index](../index.md).

UML-Klassendiagramm der vier Organe aus [§3 Organe des
Vereins](../articles/03-organe.md) und ihrer Beziehungen zueinander sowie zu
den [Mitgliedern](../concepts/Mitglied.md): [Mitgliederversammlung](../concepts/Mitgliederversammlung.md)
wählt den [Vorstand](../concepts/Vorstand.md) und die Rechnungsprüfung und
beruft den [Schlichtungsausschuss](../concepts/Schlichtungsausschuss.md).

```kuml
classDiagram(name = "Organe des Muster-Vereins") {
    showOperations = false

    val mv = classOf(name = "Mitgliederversammlung") {
        attribute(name = "turnus", type = "String")
    }
    val vorstand = classOf(name = "Vorstand") {
        attribute(name = "groesse", type = "Int")
    }
    val pruefung = classOf(name = "Rechnungspruefung")
    val schlichtung = classOf(name = "Schlichtungsausschuss")
    val mitglied = classOf(name = "Mitglied") {
        attribute(name = "beitrittsdatum", type = "Date")
    }

    association(source = mv, target = vorstand) {
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "1"); role = "waehlt" }
    }
    association(source = mv, target = pruefung) {
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "1"); role = "waehlt" }
    }
    association(source = mitglied, target = mv) {
        source { multiplicity(spec = "1..*"); role = "mitglieder" }
        target { multiplicity(spec = "1") }
    }
    association(source = mv, target = schlichtung) {
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "0..1"); role = "beruft" }
    }
}
```
