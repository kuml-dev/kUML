package dev.kuml.runtime.snapshot

/**
 * Wird von [MigrationPolicy] geworfen, wenn ein Snapshot nicht auf das aktuelle
 * Modell angewendet werden kann.
 *
 * @property reason Kurze Beschreibung des Ablehnungsgrunds.
 * @property expected Was erwartet wurde (z.B. Fingerprint oder Vertex-ID).
 * @property actual Was tatsächlich vorlag.
 */
public class MigrationException(
    public val reason: String,
    public val expected: String,
    public val actual: String,
) : RuntimeException("Migration rejected: $reason (expected=$expected, actual=$actual)")
