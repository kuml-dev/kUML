# State Machine — Operational Semantics (V1.1.5)

Spec-Version 1, Datum 2026-06-05.

Diese Datei ist verbindlich für `StateMachineRuntime` (V1.1.5). Jede Regel
hat einen oder mehrere Goldfile-Tests, die das Verhalten konkretisieren.

## Quellverweise

- ADR-0007 Executable Behaviour Runtime (`03 Bereiche/kUML/ADR/`)
- Runtime und Widget (`03 Bereiche/kUML/`)
- Designentwurf V1.1.5 (`03 Bereiche/kUML/Plan/`)

## Regeln

### Regel 1 — Initialisierung

`start(stateMachine)` betritt die Composite-State-Hierarchie ausgehend vom
`Initial`-Pseudostate. Genau ein `INITIAL` muss auf der Wurzel existieren,
sonst `IllegalStateException` mit klarer Meldung. Aus dem `INITIAL` wird die
einzige eingehende Transition genommen (sofern Guard `true` ist) und auf den
Zielvertex angewandt. `entry`-Actions werden in Top-Down-Reihenfolge der
durchquerten Composite-Hierarchie ausgeführt und als Trace-Einträge geloggt.

### Regel 2 — Event-Verarbeitung (Single-Run-to-Completion)

**2a** — Wähle Menge der enabled Transitions: deren `trigger` (per
`triggerName`-Match) das Event matcht und deren `guard` (OCL) `true`
evaluiert (oder `null` ist).

**2b** — Bei Mehrdeutigkeit gewinnt die **tiefste State-Ebene**. Bei
gleicher Tiefe **deterministisch nach Definition-Reihenfolge** in
`UmlStateMachine.transitions`.

**2c** — Ausführungsreihenfolge: `exit`-Actions (Bottom-Up vom Source bis
zum **Common Ancestor**), dann `transition.effect`, dann `entry`-Actions
(Top-Down vom Common Ancestor bis zum Target).

**2d** — Während eines Steps gepostete interne Events werden in einer
**FIFO-Queue** gehalten und nach Abschluss des aktuellen Steps abgearbeitet,
bevor das nächste externe Event verarbeitet wird (run-to-completion).

### Regel 3 — Kein Event passt

`StepResult.Stayed(reason = "no enabled transition for event '$name'")`,
Trace-Eintrag, keine Side-Effects.

### Regel 4 — Guard wirft `OclEvaluationException`

Guard zählt als `false`; Trace-Eintrag
`TraceEntry.GuardWarning(transitionId, message)`.

### Regel 5 — Action wirft Exception

Step wird **atomar abgebrochen** — Zustand bleibt **vor** der Transition.
`StepResult.Error(throwable)`, Trace-Eintrag
`TraceEntry.ActionError(transitionId, message)`. In V1.1.5 werfen Actions
praktisch nicht (sie werden nur geloggt) — die Regel ist aber Teil der Spec.

### Regel 6 — History-States außerhalb Scope

`SHALLOW_HISTORY` und `DEEP_HISTORY` sind in V1.1.5 nicht unterstützt. Wenn
ein solcher Pseudostate erreicht wird, wirft die Runtime eine
`IllegalStateException` mit Verweis auf V2.

### Regel 7 — Orthogonale Regionen außerhalb Scope

In V1.1.5 nicht unterstützt. Das Metamodell hat aktuell keine explizite
Region-Klasse — die Regel ist deshalb implizit erfüllt.

### Regel 8 — Choice / Junction / Fork / Join

`CHOICE`-Pseudostate wird unterstützt: bei Eintritt werden die ausgehenden
Transitions geprüft, die erste mit erfülltem Guard wird genommen, ohne
Event-Konsumption. `JUNCTION`, `FORK`, `JOIN` sind außerhalb Scope —
`IllegalStateException` bei Eintritt.

### Regel 9 — Final State erreicht

Runtime wechselt in `Terminated`-Modus, weitere Events werden mit
`StepResult.Stayed(reason = "state machine terminated")` quittiert.

## Tests (Querverweise)

| Regel | Test-Klasse | Status |
|---|---|---|
| 1 | `Rule1InitializationGoldfile` (T4) | offen |
| 2a | `StateMachineRuntimeFlatTest` (T2) + `Rule2aTriggerGuardGoldfile` (T4) | offen |
| 2b | `StateMachineRuntimeHierarchyTest` (T2) | offen |
| 2c | `StateMachineRuntimeHierarchyTest` (T2) + `Rule2cExitEntryOrderGoldfile` (T4) | offen |
| 2d | `StateMachineRuntimeAtomicTest` (T2) | offen |
| 3 | `StateMachineRuntimeFlatTest` (T2) + `Rule3NoMatchGoldfile` (T4) | offen |
| 4 | `OclGuardEvaluatorTest` (T3) + `Rule4GuardExceptionGoldfile` (T4) | offen |
| 5 | `StateMachineRuntimeAtomicTest` (T2) + `Rule5ActionExceptionGoldfile` (T4) | offen |
| 6 | `Rule6HistoryRejectedGoldfile` (T4) | offen |
| 7 | (Metamodell-Lücke — kein Test) | n/a |
| 8 | `StateMachineRuntimeChoiceTest` (T2) + `Rule8ChoiceAutoFireGoldfile` (T4) | offen |
| 9 | `StateMachineRuntimeFlatTest` (T2) + `Rule9FinalTerminatesGoldfile` (T4) | offen |

Status-Werte: `offen` (Test geplant, nicht implementiert), `abgedeckt`
(Test grün), `n/a` (nicht anwendbar oder Metamodell-Lücke).
