---
tags:
  - kUML/beispiel
  - kUML/bpmn
  - kUML/uml
  - kUML/m2m
status: aktiv
date: 2026-06-26
---

# BPMN zu UML-Aktivität — PdV Mitglieder-Aufnahme

Dieses Beispiel zeigt denselben Prozess in zwei äquivalenten Notationen:
- **Links**: BPMN-Prozessmodell (Quelle)
- **Rechts**: UML-Aktivitätsdiagramm (via `kuml transform --from bpmn --to uml-activity`)

Der Prozess beschreibt die Mitglieder-Aufnahme der Partei der Vernunft (PdV).

## Mapping-Tabelle

| BPMN-Element | UML-Aktivitäts-Äquivalent |
|---|---|
| `startEvent` | `initialNode()` |
| `task("Antrag einreichen")` | `action(name = "Antrag einreichen")` |
| `exclusiveGateway` (divergierend) | `decision()` |
| `exclusiveGateway` (konvergierend) | `merge()` |
| `endEvent` | `finalNode()` |
| `sequenceFlow(condition = "...")` | `edge(from = ..., to = ..., guard = "...")` |

## BPMN-Quelle

```kuml
bpmnModel("PdV Mitglieder-Aufnahme") {
    process(id = "pdvMemberProcess", name = "PdV Mitglieder-Aufnahme") {
        val start = startEvent("Interesse bekundet")
        val antrag = task("Antrag einreichen")
        val pruefung = task("Antrag prüfen")
        val entscheidung = gateway(GatewayType.EXCLUSIVE, name = "Genehmigt?")
        val bestaetigung = task("Bestätigung senden")
        val ablehnung = task("Ablehnungsschreiben senden")
        val merge = gateway(GatewayType.EXCLUSIVE)
        val end = endEvent("Prozess abgeschlossen")

        sequenceFlow(start, antrag)
        sequenceFlow(antrag, pruefung)
        sequenceFlow(pruefung, entscheidung)
        sequenceFlow(entscheidung, bestaetigung, condition = "ja")
        sequenceFlow(entscheidung, ablehnung, condition = "nein")
        sequenceFlow(bestaetigung, merge)
        sequenceFlow(ablehnung, merge)
        sequenceFlow(merge, end)
    }
    diagram("PdV Mitglieder-Aufnahme", processId = "pdvMemberProcess")
}
```

## UML-Aktivität (generiert)

```kuml
activityDiagram(name = "PdV Mitglieder-Aufnahme") {

    val start = initialNode()
    val antrag = action(name = "Antrag einreichen")
    val pruefung = action(name = "Antrag prüfen")
    val entscheidung = decision()
    val bestaetigung = action(name = "Bestätigung senden")
    val ablehnung = action(name = "Ablehnungsschreiben senden")
    val mergeNode = merge()
    val end = finalNode()

    edge(from = start, to = antrag)
    edge(from = antrag, to = pruefung)
    edge(from = pruefung, to = entscheidung)
    edge(from = entscheidung, to = bestaetigung, guard = "ja")
    edge(from = entscheidung, to = ablehnung, guard = "nein")
    edge(from = bestaetigung, to = mergeNode)
    edge(from = ablehnung, to = mergeNode)
    edge(from = mergeNode, to = end)
}
```

## Hinweise

- Der exklusive Gateway (`EXCLUSIVE`) wird beim Mapping in DECISION (1-ein, n-aus) und MERGE (n-ein, 1-aus) aufgeteilt, da UML diese Konzepte trennt.
- BPMN-Gateways mit sowohl mehreren Eingängen als auch mehreren Ausgängen (MIXED) werden in zwei UML-Knoten aufgeteilt und über die Metadaten `bpmn.sourceId` wieder zusammengeführt (Round-Trip-Stabilität).
- Pool/Lane → ActivityPartition ist Best-Effort: Lane-Namen werden als Kommentare im generierten Skript ausgegeben, da kUML keinen PARTITION-Knotentyp im UML-Aktivitätsmetamodell hat.

## CLI-Aufruf

```bash
# BPMN → UML-Aktivität
kuml transform --from bpmn --to uml-activity pdv-mitglieder.kuml.kts --output out/

# UML-Aktivität → BPMN
kuml transform --from uml-activity --to bpmn pdv-aktivitaet.kuml.kts --output out/
```
