package dev.kuml.runtime

import dev.kuml.uml.UmlStateMachine

/**
 * Marker für ausführbare Verhaltens-Modelle.
 *
 * V1.1.5 hat nur eine Variante — `StateMachine`. V2 ergänzt `Activity`,
 * V2.1 ggf. SysML-2-Equivalente. Public API verwendet die konkreten
 * Metamodell-Klassen direkt (z.B. `UmlStateMachine`); dieser Marker
 * existiert nur intern für den Interpreter-Polymorphismus.
 */
internal sealed interface KumlBehaviour {
    /** State-Machine-Wrapper. */
    class StateMachine internal constructor(
        internal val sm: UmlStateMachine,
    ) : KumlBehaviour
    // V2: class Activity(...) : KumlBehaviour
}
