package dev.kuml.ai.tools.patch.compliance

import kotlinx.serialization.json.Json

/**
 * Shared [Json] instance for encoding and decoding [ComplianceEvent] objects.
 *
 * Used by [FileComplianceSink] (write) and the `kuml ai audit` CLI command (read).
 * Both ends must use the same instance to guarantee round-trip fidelity and
 * consistent polymorphic discriminator handling.
 *
 * The default kotlinx-serialization `classDiscriminator` is `"type"`, which
 * matches the `@SerialName` annotations on [ComplianceEvent] sub-types.
 */
public object ComplianceJson {
    public val instance: Json =
        Json {
            encodeDefaults = true
            prettyPrint = false
        }
}
