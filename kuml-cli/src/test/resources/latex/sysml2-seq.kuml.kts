import dev.kuml.sysml2.CombinedFragmentOperand
import dev.kuml.sysml2.CombinedFragmentOperator
import dev.kuml.sysml2.MessageKind
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "LoginFlow") {

    // ── Lifelines (participants) ─────────────────────────────────────────
    val user = lifelineDef(name = "User")
    val browser = lifelineDef(name = "Browser")
    val authService = lifelineDef(name = "AuthService")

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
    seqDiagram(name = "Login flow") {
        include(lifeline = user)
        include(lifeline = browser)
        include(lifeline = authService)
    }
}
