package dev.kuml.ai.tools.patch

/**
 * Identifies an AI-editing session and the user who owns it.
 *
 * Passed to [PatchApplyEngine] to enable multi-user ownership validation:
 * a patch buffered by one owner cannot be applied by a different owner.
 *
 * @param sessionId  Stable ULID for this engine instance lifetime.
 * @param ownerId    Human-readable owner identifier (e.g. OS username, user UUID).
 */
public data class PatchSession(
    val sessionId: PatchSessionId,
    val ownerId: String,
) {
    public companion object {
        /** Creates a new session using the OS username as owner. */
        public fun forCurrentUser(): PatchSession =
            PatchSession(
                sessionId = PatchSessionId.newSession(),
                ownerId = System.getProperty("user.name") ?: "unknown",
            )
    }
}
