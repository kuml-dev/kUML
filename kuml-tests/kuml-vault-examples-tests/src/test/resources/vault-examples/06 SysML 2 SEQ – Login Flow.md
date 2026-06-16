---
title: SysML 2 SEQ – Login Flow
date: 2026-06-11
tags:
  - kUML
  - beispiel
  - sysml2
  - sequence
status: aktiv
---

# SysML 2 Sequence Diagram — Login Flow

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Fortgeschrittenes Beispiel: **Login-Sequence-Diagramm** mit drei Lifelines, einer Create-Message ganz oben, einem Self-Call, einer Execution Specification (Activation-Bar) und einem **Combined Fragment** (`alt`) für Happy-Path vs. Sad-Path beim Reply.

## Diagramm

```kuml
import dev.kuml.sysml2.CombinedFragmentOperand
import dev.kuml.sysml2.CombinedFragmentOperator
import dev.kuml.sysml2.MessageKind
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model("LoginFlow") {

    // ── Lifelines (participants) ─────────────────────────────────────────
    val user = lifelineDef("User")
    val browser = lifelineDef("Browser")
    val authService = lifelineDef("AuthService")

    // ── Create message (top of the interaction) ─────────────────────────
    message(
        label = "new Browser()",
        source = user,
        target = browser,
        seqNo = 0,
        kind = MessageKind.Create,
    )

    // ── Messages (sequence-ordered) ──────────────────────────────────────
    message(
        label = "enterCredentials(user, pwd)",
        source = user,
        target = browser,
        seqNo = 1,
        kind = MessageKind.Sync,
    )
    message(
        label = "login(user, pwd)",
        source = browser,
        target = authService,
        seqNo = 2,
        kind = MessageKind.Sync,
    )
    message(
        label = "validateCredentials()",
        source = authService,
        target = authService,
        seqNo = 3,
        kind = MessageKind.Sync,
    )
    message(
        label = "sessionToken",
        source = authService,
        target = browser,
        seqNo = 4,
        kind = MessageKind.Reply,
    )
    message(
        label = "welcomeScreen",
        source = browser,
        target = user,
        seqNo = 5,
        kind = MessageKind.Reply,
    )
    message(
        label = "loginError",
        source = authService,
        target = browser,
        seqNo = 6,
        kind = MessageKind.Reply,
    )
    message(
        label = "errorScreen",
        source = browser,
        target = user,
        seqNo = 7,
        kind = MessageKind.Reply,
    )

    // ── Execution Specification on AuthService ──────────────────────────
    executionSpec(
        name = "authServiceActive",
        lifeline = authService,
        startSeqNo = 2,
        endSeqNo = 3,
    )

    // ── Combined Fragment — `alt` over the reply phase ──────────────────
    // Beide Operanden bekommen eigene Nachrichten-Spannen, damit der
    // Operand-Separator des Sad-Paths zwischen `welcomeScreen` (seqNo=5)
    // und `loginError` (seqNo=6) eingezogen wird und der Frame beide
    // Branches umschließt. Ein Sad-Path-Operand mit Null-Spanne
    // (`startSeqNo == endSeqNo == letzter Happy-Path-seqNo`) würde vom
    // V2.0.44-Guard im Renderer aus dem Frame herausgeschoben — siehe
    // [[03 Bereiche/kUML/Architektur#SEQ-Renderer-Operand-Guards]].
    combinedFragment(
        name = "credentialsCheck",
        operator = CombinedFragmentOperator.Alt,
        operands = listOf(
            CombinedFragmentOperand(guard = "credentials valid", startSeqNo = 4, endSeqNo = 5),
            CombinedFragmentOperand(guard = "credentials invalid", startSeqNo = 6, endSeqNo = 7),
        ),
    )

    // ── Sequence Diagram ────────────────────────────────────────────────
    seqDiagram("Login flow") {
        include(user)
        include(browser)
        include(authService)
    }
}
```

## Was hier alles drin steckt

| Konstrukt | Wo im Code | Visuell |
|---|---|---|
| **Lifeline** | `lifelineDef("User")` | Vertikale Lane mit Kopf-Box |
| **Sync-Message** | `kind = MessageKind.Sync` | Durchgezogener Pfeil mit ausgefülltem Pfeilkopf |
| **Reply-Message** | `kind = MessageKind.Reply` | Gestrichelter Pfeil mit offenem Pfeilkopf |
| **Create-Message** | `kind = MessageKind.Create` | Pfeil zur Lifeline-Box (statt zur Linie) |
| **Self-Call** | `source = authService, target = authService` | U-förmiger Pfeil zurück auf dieselbe Lifeline |
| **Execution Specification** | `executionSpec(name, lifeline, startSeqNo, endSeqNo)` | Schmale Aktivierungs-Bar auf der Lifeline-Linie |
| **Combined Fragment** | `combinedFragment(name, operator, operands)` | Gestrichelter Rahmen mit Operator-Tag (`alt`, `opt`, `loop`, …) |

## Architektur-Hinweis

> [!note] Warum SEQ einen eigenen Render-Pfad hat
> Anders als BDD/IBD/STM/ACT erzeugt der Layout-Bridge bei SEQ **keine** LayoutGraph-Edges aus den Messages. Grund: ELK's hierarchisches Layout passt nicht auf die SEQ-Konvention (Lifelines = feste X-Spuren, Messages = horizontale Pfeile an seqNo-indizierten Y-Positionen). Der SVG-Renderer zeichnet Messages, Fragments und ExecSpecs **direkt aus dem Modell**. Siehe V2.0.11 in [[02 Projekte/kUML V2.0]].

## Combined Fragments — die 8 Operatoren

`CombinedFragmentOperator` kennt die acht UML-/SysML-Standard-Operatoren:

| Operator | Bedeutung |
|---|---|
| **Alt** | Alternative (Fallunterscheidung) — wie in diesem Beispiel |
| **Opt** | Optional — wird nur ausgeführt, wenn Guard true |
| **Loop** | Schleife über die enthaltenen Messages |
| **Par** | Parallele Ausführung mehrerer Operanden |
| **Strict** | Strikte Sequenz-Reihenfolge erzwungen |
| **Seq** | Schwache Sequenz (Default-Ordnung) |
| **Break** | Frühzeitiger Ausstieg aus übergeordnetem Fragment |
| **Critical** | Atomare Region — keine Unterbrechungen |

## Verwandte Beispiele

- [[05 SysML 2 UC – Library System]] — Use Cases auf höherer Ebene, die diese Sequence implementieren würden
- [[04 SysML 2 STM – Traffic Light]] — Reaktive Behaviour-Modellierung mit Zustandsmaschine statt mit Sequence

## Verwandte Vault-Notizen

- [[02 Projekte/kUML V2.0#SysML-2-Diagrammtyp-Serie ist KOMPLETT (8/8)]]
