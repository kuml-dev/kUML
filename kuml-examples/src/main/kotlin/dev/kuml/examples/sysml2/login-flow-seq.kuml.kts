@file:Suppress("unused")

import dev.kuml.sysml2.MessageKind
import dev.kuml.sysml2.dsl.sysml2Model

/**
 * Login Flow — SysML 2 Sequence Diagram example (V2.0.11 MVP).
 *
 * Illustriert die V2.0.11-Oberfläche end-to-end mit allen drei Nachrichten-
 * Kinds und einem Self-Call:
 *  - Drei **Lifelines** `User`, `Browser`, `AuthService` als vertikale Lanes.
 *  - Fünf **Messages**:
 *    1. `User → Browser`: `enterCredentials(user, pwd)` (Sync)
 *    2. `Browser → AuthService`: `login(user, pwd)` (Sync)
 *    3. `AuthService → AuthService`: `validateCredentials()` (Sync, Self-Call)
 *    4. `AuthService → Browser`: `sessionToken` (Reply)
 *    5. `Browser → User`: `welcomeScreen` (Reply)
 *
 * Architektur-Hinweis: Messages leben wie STM-Transitionen und ACT-Flows auf
 * dem Modell (`message(...)` registriert in `Sysml2Model.usages`), nicht auf
 * dem Diagramm. **Aber**: anders als bei STM / ACT erzeugt der Layout-Bridge
 * keine LayoutGraph-Edges aus den Nachrichten — der Renderer zeichnet sie
 * direkt nach dem Standard-Knoten-Loop. Begründung: ELKs hierarchisches
 * Layout passt nicht auf die SEQ-Konvention (Lifelines = feste X-Spuren,
 * Messages = horizontale Pfeile an seqNo-indizierten Y-Positionen). Siehe
 * KDoc auf `Sysml2LayoutBridge.toLayoutGraph(SeqDiagram)` und
 * `Sysml2SequenceSvg.kt` für die ausführliche Architektur-Begründung.
 *
 * Out of V2.0.11 scope (siehe Wave-Plan):
 *  - Combined Fragments (`alt` / `opt` / `loop` / `par` / `strict`)
 *  - Execution Specifications (Aktivierungs-Rechtecke entlang der Lifeline)
 *  - `Create` / `Destroy` Lifecycle-Nachrichten
 *  - Found / Lost Messages (von / nach außen)
 *  - Co-region / general-ordering constraints
 *  - Time / duration constraint annotations
 *  - PNG-Export für SysML 2 SEQ
 */
sysml2Model("LoginFlow") {

    // ── Lifelines (participants) ─────────────────────────────────────────
    val user = lifelineDef("User")
    val browser = lifelineDef("Browser")
    val authService = lifelineDef("AuthService")

    // ── Messages (sequence-ordered) ──────────────────────────────────────
    message(
        label = "enterCredentials(user, pwd)",
        source = user,
        target = browser,
        seqNo = 0,
        kind = MessageKind.Sync,
    )
    message(
        label = "login(user, pwd)",
        source = browser,
        target = authService,
        seqNo = 1,
        kind = MessageKind.Sync,
    )
    // Self-Call: AuthService validiert intern.
    message(
        label = "validateCredentials()",
        source = authService,
        target = authService,
        seqNo = 2,
        kind = MessageKind.Sync,
    )
    message(
        label = "sessionToken",
        source = authService,
        target = browser,
        seqNo = 3,
        kind = MessageKind.Reply,
    )
    message(
        label = "welcomeScreen",
        source = browser,
        target = user,
        seqNo = 4,
        kind = MessageKind.Reply,
    )

    // ── Sequence Diagram ─────────────────────────────────────────────────
    seqDiagram("Login flow") {
        include(user)
        include(browser)
        include(authService)
    }
}
