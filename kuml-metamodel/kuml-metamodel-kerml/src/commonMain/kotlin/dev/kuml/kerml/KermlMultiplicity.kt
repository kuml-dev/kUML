package dev.kuml.kerml

import kotlinx.serialization.Serializable

/**
 * Multiplicity range — `lower..upper`, with `null` upper meaning "unbounded"
 * (the SysML 2 spec uses `*` in concrete syntax).
 *
 * Defaults to `1..1` (exactly one), matching the KerML default for features
 * without an explicit multiplicity annotation.
 *
 * Wraps two `Int`s rather than the boxier `IntRange` so it serialises
 * cleanly via kotlinx.serialization and survives the `null` upper bound.
 */
@Serializable
data class KermlMultiplicity(
    val lower: Int = 1,
    /** `null` means unbounded (`*`). */
    val upper: Int? = 1,
) {
    init {
        require(lower >= 0) { "KermlMultiplicity.lower must be non-negative, was $lower" }
        require(upper == null || upper >= lower) {
            "KermlMultiplicity.upper ($upper) must be null or >= lower ($lower)"
        }
    }

    /** SysML 2 concrete-syntax form, e.g. `1..*`, `0..1`, `3`. */
    fun toSpecForm(): String =
        when {
            upper == null -> "$lower..*"
            lower == upper -> "$lower"
            else -> "$lower..$upper"
        }

    override fun toString(): String = toSpecForm()

    companion object {
        /** `1..1` — the KerML/SysML 2 default. */
        val EXACTLY_ONE: KermlMultiplicity = KermlMultiplicity(1, 1)

        /** `0..1` — typical for optional ports / attributes. */
        val OPTIONAL: KermlMultiplicity = KermlMultiplicity(0, 1)

        /** `0..*` — unbounded, including zero. */
        val ZERO_OR_MORE: KermlMultiplicity = KermlMultiplicity(0, null)

        /** `1..*` — unbounded, at least one. */
        val ONE_OR_MORE: KermlMultiplicity = KermlMultiplicity(1, null)
    }
}
