package dev.kuml.runtime

/**
 * Strategie zum Auswerten von OCL-Guard-Strings.
 *
 * V1.1.5 Ticket 2 verwendet die Stub-Implementierung [AlwaysTrue] in Tests,
 * Ticket 3 liefert die OCL-basierte Default-Implementierung.
 */
public fun interface GuardEvaluator {
    public fun evaluate(
        guard: String?,
        instance: ModelInstance<*>,
        event: Event,
    ): GuardResult

    public companion object {
        /** Test-Stub: alle Guards sind `true` (auch `null`). */
        public val AlwaysTrue: GuardEvaluator = GuardEvaluator { _, _, _ -> GuardResult.True }
    }
}

/** Resultat einer Guard-Auswertung. */
public sealed interface GuardResult {
    public data object True : GuardResult

    public data object False : GuardResult

    /** Guard warf eine Exception → wird als `false` gewertet, Trace-Warnung wird vom Caller geloggt. */
    public data class Failed(
        public val message: String,
    ) : GuardResult
}
