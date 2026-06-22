@file:Suppress("unused")

import dev.kuml.sysml2.CombinedFragmentOperand
import dev.kuml.sysml2.CombinedFragmentOperator
import dev.kuml.sysml2.MessageKind
import dev.kuml.sysml2.dsl.sysml2Model

/**
 * Login Flow — SysML 2 Sequence Diagram example (V2.0.11 baseline +
 * V2.0.15 polish: Execution Specification + Alt Combined Fragment +
 * a sample Create message at the top).
 *
 * Illustriert die V2.0.11- und V2.0.15-Oberfläche end-to-end:
 *  - Drei **Lifelines** `User`, `Browser`, `AuthService` als vertikale Lanes.
 *  - Ein **Create-Pfeil** ganz oben: User instanziert Browser implizit.
 *  - Fünf **Messages** im Login-Ablauf:
 *    1. `User → Browser`: `enterCredentials(user, pwd)` (Sync, seqNo=1)
 *    2. `Browser → AuthService`: `login(user, pwd)` (Sync, seqNo=2)
 *    3. `AuthService → AuthService`: `validateCredentials()` (Sync Self-Call, seqNo=3)
 *    4. `AuthService → Browser`: `sessionToken` (Reply, seqNo=4)
 *    5. `Browser → User`: `welcomeScreen` (Reply, seqNo=5)
 *  - Ein **Execution Specification** auf `AuthService` während der Validierung
 *    (seqNo 2..3) — die thin Activation-Bar zeigt, dass der Service aktiv
 *    rechnet.
 *  - Ein **Combined Fragment** `Alt` über die Reply-Phase (seqNo 4..5) mit
 *    zwei Operanden: "credentials valid" (Happy-Path, beide Replies) vs.
 *    "credentials invalid" (Sad-Path).
 *
 * Architektur-Hinweis: Messages, Fragments und ExecSpecs leben wie STM-Transitionen
 * und ACT-Flows auf dem Modell (`message(...)` / `combinedFragment(...)` /
 * `executionSpec(...)` registrieren in `Sysml2Model.usages`), nicht auf dem
 * Diagramm. **Aber**: anders als bei STM / ACT erzeugt der Layout-Bridge
 * keine LayoutGraph-Edges aus diesen Usages — der SVG-Renderer zeichnet sie
 * direkt nach dem Standard-Knoten-Loop. Begründung: ELKs hierarchisches
 * Layout passt nicht auf die SEQ-Konvention (Lifelines = feste X-Spuren,
 * Messages = horizontale Pfeile an seqNo-indizierten Y-Positionen). Siehe
 * KDoc auf `Sysml2LayoutBridge.toLayoutGraph(SeqDiagram)` und
 * `Sysml2SequenceSvg.kt` für die ausführliche Architektur-Begründung.
 *
 * Out of V2.0.15 scope (siehe Wave-Plan):
 *  - Nested Combined Fragments (CF inside CF).
 *  - Nested Execution Specifications (overlapping activations).
 *  - Die restlichen 4 CF-Operatoren (assert, neg, consider, ignore).
 *  - Found / Lost Messages (von / nach außen).
 *  - Co-region / general-ordering constraints.
 *  - Time / duration constraint annotations.
 *  - LaTeX-Rendering für CF / ExecSpec / Create / Destroy (LaTeX bleibt im
 *    V2.0.11-Lifeline-Box-Fallback).
 */
sysml2Model(name = "LoginFlow") {

    // ── Lifelines (participants) ─────────────────────────────────────────
    val user = lifelineDef(name = "User")
    val browser = lifelineDef(name = "Browser")
    val authService = lifelineDef(name = "AuthService")

    // ── V2.0.15: Create message (top of the interaction) ─────────────────
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
    // Self-Call: AuthService validiert intern.
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

    // ── V2.0.15: Execution Specification on AuthService ──────────────────
    // The auth service is "active" from receiving the login call through
    // its self-validation step (seqNo 2..3).
    executionSpec(
        name = "authServiceActive",
        lifeline = authService,
        startSeqNo = 2,
        endSeqNo = 3,
    )

    // ── V2.0.15: Combined Fragment — `alt` over the reply phase ──────────
    // Two operands: the happy path (credentials valid) covers the two
    // Reply messages; the sad path (credentials invalid) has no further
    // messages in this MVP — V2.x can extend with an error-Reply.
    combinedFragment(
        name = "credentialsCheck",
        operator = CombinedFragmentOperator.Alt,
        operands =
            listOf(
                CombinedFragmentOperand(guard = "credentials valid", startSeqNo = 4, endSeqNo = 5),
                CombinedFragmentOperand(guard = "credentials invalid", startSeqNo = 5, endSeqNo = 5),
            ),
    )

    // ── Sequence Diagram ─────────────────────────────────────────────────
    seqDiagram(name = "Login flow") {
        include(lifeline = user)
        include(lifeline = browser)
        include(lifeline = authService)
    }
}
