package dev.kuml.io.emf

import dev.kuml.profile.KumlProfile

/**
 * Result type for [ProfileXmiImporter.importResult] — avoids throwing on malformed input.
 *
 * V3.1.41: EMF Profile Conversion.
 */
public sealed class ProfileResult {
    /** Import succeeded; [profile] contains the reconstructed [KumlProfile]. */
    public data class Success(
        val profile: KumlProfile,
    ) : ProfileResult()

    /** Import failed; [message] describes the problem, [cause] holds the original throwable. */
    public data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : ProfileResult()
}
