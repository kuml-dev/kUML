---
title: UML Timing – TCP Handshake
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - uml
  - timing
status: aktiv
---

# UML Timing-Diagramm — TCP Handshake

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **UML-Timing-Diagramm** zeigt die *Zustandsänderungen eines Objekts entlang einer Zeitachse* — besonders nützlich für Protokoll-, Hardware- oder Embedded-Systeme. Hier: der klassische TCP-3-Wege-Handshake aus Sicht des Clients und Servers.

## Diagramm

```kuml
timingDiagram(name = "TCP 3-Way Handshake") {
    lifeline(name = "client", states = listOf("CLOSED", "SYN_SENT", "ESTABLISHED")) {
        tick(t = 0, state = "CLOSED")
        tick(t = 1, state = "SYN_SENT")
        tick(t = 3, state = "ESTABLISHED")
    }

    lifeline(name = "server", states = listOf("LISTEN", "SYN_RCVD", "ESTABLISHED")) {
        tick(t = 0, state = "LISTEN")
        tick(t = 2, state = "SYN_RCVD")
        tick(t = 3, state = "ESTABLISHED")
    }
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `timingDiagram(name = …) { … }` | Top-Level: erzeugt ein Timing-Diagramm. |
| `lifeline(name = …, states = listOf(…)) { … }` | Eine Zeile im Diagramm — ein Objekt mit den möglichen Zuständen. |
| `tick(t = 0, state = "CLOSED")` | Ein Zeitpunkt mit dem aktuellen Zustand der Lifeline. |
| (Mehrere `tick`s pro Lifeline) | Ergeben die Zustandsfolge entlang der Zeitachse. |

## Mögliche Erweiterungen

- **Constraints**: minimum/maximum Zeitdauer zwischen Ticks
- **Events**: gestrichelte Linien, die Lifelines koppeln (z. B. „SYN" als Ereignis)
- **Wertbasiert statt zustandsbasiert**: numerische Werte (z. B. Spannung, Temperatur) auf einer Y-Achse

## Verwandte Beispiele

- [[18 UML State Machine – Order Lifecycle]] — Zustände ohne Zeitachse
- [[19 UML Sequence – API Submit]] — alternative Zeitsicht über Lifelines
