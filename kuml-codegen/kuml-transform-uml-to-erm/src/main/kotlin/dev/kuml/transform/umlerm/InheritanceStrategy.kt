package dev.kuml.transform.umlerm

/**
 * How a UML generalization hierarchy is materialised into ERM tables by
 * [UmlToErmTransformer].
 *
 * Selected per hierarchy root via `«Inheritance».strategy` (see
 * [dev.kuml.profile.erm.ErmMappingProfile]) on the supertype class, falling
 * back to [TransformContext.options]`["inheritance"]`, falling back to
 * [JOINED].
 *
 * V3.4.6
 */
public enum class InheritanceStrategy {
    /** One table per concrete class; subtypes are `weak` and identifying-related to the supertype. */
    JOINED,

    /** One table for the whole hierarchy; subtype columns become nullable, plus a discriminator column. */
    SINGLE_TABLE,

    /** One table per concrete class, each carrying all inherited columns; no shared supertype table. */
    TABLE_PER_CLASS,
    ;

    public companion object {
        /** Parses [raw] case-insensitively; returns `null` if it does not match a known strategy. */
        public fun fromTag(raw: String?): InheritanceStrategy? = raw?.let { runCatching { valueOf(it.trim().uppercase()) }.getOrNull() }
    }
}
