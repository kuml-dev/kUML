package dev.kuml.runtime.tokenflow

/**
 * Execution limits enforced by [TokenFlowEngine.run] (ADR-0015 §3.3).
 *
 * [dev.kuml.runtime.activity.ActivityRuntime]'s only guard against runaway
 * execution is `maxSteps`, which protects against infinite *loops* but not
 * against token *explosion*: a cyclic model with a `Fork` inside the cycle
 * doubles [dev.kuml.runtime.activity.ActivityInstance.tokenCounts] every
 * round — at `maxSteps = 1000` that is 2^1000 tokens long before the step
 * limit is ever reached. [maxTokens], [maxTraceEntries], and
 * [wallClockBudgetMs] close that gap.
 */
public data class TokenFlowLimits(
    public val maxSteps: Int = DEFAULT_MAX_STEPS,
    public val maxTokens: Int = DEFAULT_MAX_TOKENS,
    public val maxTraceEntries: Int = DEFAULT_MAX_TRACE_ENTRIES,
    public val wallClockBudgetMs: Long = DEFAULT_WALL_CLOCK_MS,
) {
    public companion object {
        /** Matches [dev.kuml.runtime.activity.ActivityRuntime]'s historical default. */
        public const val DEFAULT_MAX_STEPS: Int = 1_000
        public const val DEFAULT_MAX_TOKENS: Int = 10_000
        public const val DEFAULT_MAX_TRACE_ENTRIES: Int = 100_000
        public const val DEFAULT_WALL_CLOCK_MS: Long = 30_000L

        /** Tight limits for untrusted or exploratory models (e.g. a `--sandbox` "strict" preset). */
        public val Strict: TokenFlowLimits =
            TokenFlowLimits(
                maxSteps = 200,
                maxTokens = 512,
                maxTraceEntries = 10_000,
                wallClockBudgetMs = 5_000L,
            )
    }
}

/** Behavioural options for [TokenFlowEngine] beyond hard limits. */
public data class TokenFlowOptions(
    /**
     * When `true` (default), a step in which no node can fire and the marking
     * is non-empty is reported as [TokenFlowOutcome.Blocked] by
     * [TokenFlowEngine.run]. When `false`, it is reported as
     * [TokenFlowOutcome.Idle] instead (matches
     * `ActivityRuntime.run(failOnDeadlock = false)`).
     */
    public val failOnDeadlock: Boolean = true,
    /**
     * When `true`, an exclusive or inclusive diverge with no matching guard
     * and no default edge is a hard [TokenFlowOutcome.Failed] instead of a
     * silently dropped token.
     */
    public val strictGuardCoverage: Boolean = false,
)
