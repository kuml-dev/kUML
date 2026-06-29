---
tags: [kUML, BPMN, beispiel, smil, animiert]
status: aktiv
date: 2026-06-26
---

# BPMN animiert – PdV Mitgliedsantrag

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **animiertes BPMN-Prozessdiagramm** mit SMIL-Tokenanimation zeigt, wie ein PdV-Mitgliedsantrag von der Einreichung bis zur Bestätigung oder Nachforderung durchläuft. Das Token wandert sichtbar durch den Prozess — Start → Antrag-Prüfung → Entscheidung → Bestätigung → Ende.

## Animiertes Diagramm (kuml-animated)

> [!tip] Obsidian Plugin v0.3.0+
> Der `kuml-animated`-Code-Block mit `// trace:` Direktive rendert den BPMN-Prozess mit token-getriebenem SMIL-Token, der durch den Happy-Path wandert. Die Animation läuft automatisch **5× in Schleife** (~32 Sekunden gesamt), dann stoppt sie. Plugin-Reload oder Notiz-Wechsel startet sie neu.

```kuml-animated
// trace: 07 Anhänge/kUML/traces/bpmn-pdv-mitgliedsantrag.kuml.trace.v1.json
import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

bpmnModel(name = "PdV Mitgliedsantrag") {
    process(id = "p_antrag", name = "Mitgliedsantrag-Prozess") {
        val start        = startEvent(name = "Antrag eingegangen")
        val pruefung     = task(name = "Antrag-Prüfung", type = TaskType.SERVICE)
        val gw           = gateway(type = GatewayType.EXCLUSIVE, name = "Vollständig?")
        val bestaetigung = task(name = "Bestätigung", type = TaskType.SEND)
        val nachforderung = task(name = "Nachforderung", type = TaskType.SEND)
        val endOk        = endEvent(name = "Mitglied bestätigt")
        val endNachf     = endEvent(name = "Nachforderung gesendet")

        sequenceFlow(from = start, to = pruefung)
        sequenceFlow(from = pruefung, to = gw)
        sequenceFlow(from = gw, to = bestaetigung, condition = "vollständig == true", name = "Ja")
        sequenceFlow(from = gw, to = nachforderung, condition = "vollständig == false", name = "Nein")
        sequenceFlow(from = bestaetigung, to = endOk)
        sequenceFlow(from = nachforderung, to = endNachf)
    }
    diagram(name = "PdV Mitgliedsantrag", processId = "p_antrag")
}
```

## Statisches Diagramm (Referenz)

```kuml
import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

bpmnModel(name = "PdV Mitgliedsantrag") {
    process(id = "p_antrag", name = "Mitgliedsantrag-Prozess") {
        val start       = startEvent(name = "Antrag eingegangen")
        val pruefung    = task(name = "Antrag-Prüfung", type = TaskType.SERVICE)
        val gw          = gateway(type = GatewayType.EXCLUSIVE, name = "Vollständig?")
        val bestaetigung = task(name = "Bestätigung", type = TaskType.SEND)
        val nachforderung = task(name = "Nachforderung", type = TaskType.SEND)
        val endOk       = endEvent(name = "Mitglied bestätigt")
        val endNachf    = endEvent(name = "Nachforderung gesendet")

        sequenceFlow(from = start, to = pruefung)
        sequenceFlow(from = pruefung, to = gw)
        sequenceFlow(from = gw, to = bestaetigung, condition = "vollständig == true", name = "Ja")
        sequenceFlow(from = gw, to = nachforderung, condition = "vollständig == false", name = "Nein")
        sequenceFlow(from = bestaetigung, to = endOk)
        sequenceFlow(from = nachforderung, to = endNachf)
    }
    diagram(name = "PdV Mitgliedsantrag", processId = "p_antrag")
}
```

<!-- trace: siehe JSON-Block unten -->

```json
{
  "schema": "kuml.trace.v1",
  "modelId": "PdV Mitgliedsantrag",
  "entries": [
    { "type": "TokenPlaced",    "seqNo": 0, "timestamp": "2026-06-26T10:00:00Z", "nodeId": "p_antrag_start_1",       "clock": 0 },
    { "type": "TokenConsumed",  "seqNo": 1, "timestamp": "2026-06-26T10:00:01Z", "nodeId": "p_antrag_start_1",       "clock": 1 },
    { "type": "TokenPlaced",    "seqNo": 2, "timestamp": "2026-06-26T10:00:02Z", "nodeId": "p_antrag_task_2",        "clock": 2 },
    { "type": "TokenConsumed",  "seqNo": 3, "timestamp": "2026-06-26T10:00:03Z", "nodeId": "p_antrag_task_2",        "clock": 3 },
    { "type": "DecisionTaken",  "seqNo": 4, "timestamp": "2026-06-26T10:00:04Z", "nodeId": "p_antrag_gw_3",  "chosenEdgeId": "p_antrag_flow_3", "guard": "vollständig == true", "clock": 4 },
    { "type": "TokenPlaced",    "seqNo": 5, "timestamp": "2026-06-26T10:00:05Z", "nodeId": "p_antrag_task_4",        "clock": 5 },
    { "type": "TokenConsumed",  "seqNo": 6, "timestamp": "2026-06-26T10:00:06Z", "nodeId": "p_antrag_task_4",        "clock": 6 },
    { "type": "TokenPlaced",    "seqNo": 7, "timestamp": "2026-06-26T10:00:07Z", "nodeId": "p_antrag_end_6",         "clock": 7 }
  ]
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `startEvent(name = …)` | Startereignis — das Token beginnt hier. |
| `task(name = …, type = TaskType.SERVICE)` | Service-Task für die automatische Antragsprüfung. |
| `gateway(type = GatewayType.EXCLUSIVE, name = …)` | Exklusives Gateway — nur ein Pfad wird gewählt. |
| `task(name = …, type = TaskType.SEND)` | Send-Task für Bestätigung oder Nachforderung. |
| `endEvent(name = …)` | Endereignis — Token wird konsumiert. |
| `sequenceFlow(from = …, to = …, condition = …)` | Verbindung mit optionaler Bedingung. |

## SMIL-Animation

Die Trace-JSON im `json`-Block oben beschreibt den "Happy Path" — Antrag vollständig, direkte Bestätigung. Die Element-IDs folgen dem kUML-DSL-Schema für `process(id = "p_antrag")`:

| DSL-Aufruf | ID |
|---|---|
| `startEvent(…)` — 1. Element | `p_antrag_start_1` |
| `task(…)` — 2. Element | `p_antrag_task_2` |
| `gateway(…)` — 3. Element | `p_antrag_gw_3` |
| `task("Bestätigung")` — 4. Element | `p_antrag_task_4` |
| `task("Nachforderung")` — 5. Element | `p_antrag_task_5` |
| `endEvent("Mitglied bestätigt")` — 6. Element | `p_antrag_end_6` |
| `endEvent("Nachforderung gesendet")` — 7. Element | `p_antrag_end_7` |
| Flows | `p_antrag_flow_1` … `p_antrag_flow_6` |

## Verwandte Beispiele

- [[30 BPMN Process – Order Fulfillment]] — statisches BPMN ohne Animation
- [[31 BPMN Process – Sub-Process Loop]] — Schleifensubprozess
- [[08 STM animiert – Traffic Light]] — animiertes STM-Pendant
