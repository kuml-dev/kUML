---
tags: [kUML, UML, beispiel, smil, animiert, state-machine]
status: aktiv
date: 2026-06-26
---

# STM animiert – Traffic Light

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **animiertes UML-Zustandsdiagramm** mit SMIL-Zustandsanimation zeigt eine Ampel-Steuerung. Die Zustände Red → Green → Yellow wechseln sichtbar — zwei vollständige Zyklen werden in der Trace erfasst. Das Diagramm nutzt die UML `stateDiagram`-DSL (nicht SysML 2), da `StmSmilRenderer` eine `UmlStateMachine` benötigt.

## Diagramm

```kuml
stateDiagram(name = "Traffic Light") {
    val initial  = initialState()
    val red      = state(name = "Red")   { entry = "activateRed()" }
    val green    = state(name = "Green") { entry = "activateGreen()" }
    val yellow   = state(name = "Yellow") { entry = "activateYellow()" }
    val off      = finalState(name = "Off")

    transition(source = initial, target = red)
    transition(source = red, target = green) {
        trigger = "timerGreen"
        guard   = "[cycleActive]"
    }
    transition(source = green, target = yellow) {
        trigger = "timerYellow"
    }
    transition(source = yellow, target = red) {
        trigger = "timerRed"
        guard   = "[cycleActive]"
    }
    transition(source = yellow, target = off) {
        trigger = "powerOff"
        guard   = "[!cycleActive]"
    }
}
```

<!-- trace: siehe JSON-Block unten -->

```json
{
  "schema": "kuml.trace.v1",
  "modelId": "Traffic Light",
  "entries": [
    { "type": "StateEntered",     "seqNo": 0,  "timestamp": "2026-06-26T10:00:00Z", "vertexId": "Traffic Light::initial" },
    { "type": "TransitionFired",  "seqNo": 1,  "timestamp": "2026-06-26T10:00:00Z", "transitionId": "Traffic Light::t::initial->Red",    "fromVertexId": "Traffic Light::initial", "toVertexId": "Traffic Light::Red" },
    { "type": "StateEntered",     "seqNo": 2,  "timestamp": "2026-06-26T10:00:01Z", "vertexId": "Traffic Light::Red" },
    { "type": "TransitionFired",  "seqNo": 3,  "timestamp": "2026-06-26T10:00:30Z", "transitionId": "Traffic Light::t::Red->Green",       "fromVertexId": "Traffic Light::Red",     "toVertexId": "Traffic Light::Green" },
    { "type": "StateEntered",     "seqNo": 4,  "timestamp": "2026-06-26T10:00:31Z", "vertexId": "Traffic Light::Green" },
    { "type": "TransitionFired",  "seqNo": 5,  "timestamp": "2026-06-26T10:01:01Z", "transitionId": "Traffic Light::t::Green->Yellow",    "fromVertexId": "Traffic Light::Green",   "toVertexId": "Traffic Light::Yellow" },
    { "type": "StateEntered",     "seqNo": 6,  "timestamp": "2026-06-26T10:01:02Z", "vertexId": "Traffic Light::Yellow" },
    { "type": "TransitionFired",  "seqNo": 7,  "timestamp": "2026-06-26T10:01:07Z", "transitionId": "Traffic Light::t::Yellow->Red",      "fromVertexId": "Traffic Light::Yellow",  "toVertexId": "Traffic Light::Red" },
    { "type": "StateEntered",     "seqNo": 8,  "timestamp": "2026-06-26T10:01:08Z", "vertexId": "Traffic Light::Red" },
    { "type": "TransitionFired",  "seqNo": 9,  "timestamp": "2026-06-26T10:01:38Z", "transitionId": "Traffic Light::t::Red->Green",       "fromVertexId": "Traffic Light::Red",     "toVertexId": "Traffic Light::Green" },
    { "type": "StateEntered",     "seqNo": 10, "timestamp": "2026-06-26T10:01:39Z", "vertexId": "Traffic Light::Green" },
    { "type": "TransitionFired",  "seqNo": 11, "timestamp": "2026-06-26T10:02:09Z", "transitionId": "Traffic Light::t::Green->Yellow",    "fromVertexId": "Traffic Light::Green",   "toVertexId": "Traffic Light::Yellow" },
    { "type": "StateEntered",     "seqNo": 12, "timestamp": "2026-06-26T10:02:10Z", "vertexId": "Traffic Light::Yellow" },
    { "type": "TransitionFired",  "seqNo": 13, "timestamp": "2026-06-26T10:02:15Z", "transitionId": "Traffic Light::t::Yellow->Red",      "fromVertexId": "Traffic Light::Yellow",  "toVertexId": "Traffic Light::Red" },
    { "type": "StateEntered",     "seqNo": 14, "timestamp": "2026-06-26T10:02:16Z", "vertexId": "Traffic Light::Red" }
  ]
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `stateDiagram(name = "Traffic Light") { … }` | Top-Level: erzeugt eine UML-State Machine mit id = "Traffic Light". |
| `initialState()` | Pseudostate INITIAL — id = "Traffic Light::initial". |
| `state(name = "Red") { entry = … }` | Einfacher Zustand — id = "Traffic Light::Red". |
| `finalState(name = "Off")` | Final-Zustand — id = "Traffic Light::Off". |
| `transition(source, target) { trigger = …; guard = … }` | Zustandsübergang mit Auslöser und Bedingung. |

## Vertex- und Transitions-IDs (für Trace-JSON)

Die IDs folgen dem Schema `UmlIds.vertex(smId, name)` und `UmlIds.transition(smId, srcName, tgtName)`:

| Element | ID |
|---|---|
| `initialState()` | `Traffic Light::initial` |
| `state("Red")` | `Traffic Light::Red` |
| `state("Green")` | `Traffic Light::Green` |
| `state("Yellow")` | `Traffic Light::Yellow` |
| `finalState("Off")` | `Traffic Light::Off` |
| initial → Red | `Traffic Light::t::initial->Red` |
| Red → Green | `Traffic Light::t::Red->Green` |
| Green → Yellow | `Traffic Light::t::Green->Yellow` |
| Yellow → Red | `Traffic Light::t::Yellow->Red` |
| Yellow → Off | `Traffic Light::t::Yellow->Off` |

## Verwandte Beispiele

- [[04 SysML 2 STM – Traffic Light]] — SysML-2-Pendant (ohne SMIL-Animation)
- [[18 UML State Machine – Order Lifecycle]] — weiteres UML-State-Machine-Beispiel
- [[07 BPMN animiert – PdV Mitgliedsantrag]] — animiertes BPMN-Pendant
