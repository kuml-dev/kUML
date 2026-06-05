package dev.kuml.runtime

/**
 * Resultat eines [BehaviourInterpreter.step]-Aufrufs.
 */
public sealed interface StepResult {
    public data class Transitioned(
        public val fromVertexIds: List<String>,
        public val toVertexIds: List<String>,
        public val transitionIds: List<String>,
    ) : StepResult

    public data class Stayed(
        public val reason: String,
    ) : StepResult

    public data class GuardFailed(
        public val transitionId: String,
        public val message: String,
    ) : StepResult

    public data class Error(
        public val cause: Throwable,
    ) : StepResult

    public data object Terminated : StepResult
}
