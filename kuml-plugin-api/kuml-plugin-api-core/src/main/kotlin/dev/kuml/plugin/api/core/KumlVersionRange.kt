package dev.kuml.plugin.api.core

import kotlinx.serialization.Serializable

/**
 * Semver-compatible version range for plugin compatibility checks.
 *
 * Syntax: `>=3.0.27, <4.0` or `[3.0.27,4.0.0)` (Maven-range style).
 * Both styles are accepted by [parse]; [toString] normalises to the `>=X, <Y` form.
 */
@Serializable
public data class KumlVersionRange(
    val raw: String,
) {
    /** Returns true if [version] satisfies this range. */
    public fun contains(version: PluginVersion): Boolean =
        try {
            parseAndCheck(raw, version)
        } catch (_: Exception) {
            false // lenient: unparseable range never matches
        }

    public companion object {
        /** Wildcard range that accepts any kUML version. */
        public val ANY: KumlVersionRange = KumlVersionRange(">=0.0.0")

        private val MAVEN_RANGE_REGEX = Regex("""^\[(.+),(.+)]$""")

        private fun parseAndCheck(
            raw: String,
            version: PluginVersion,
        ): Boolean {
            // Handle Maven [X,Y] range style first (contains a comma inside brackets)
            val mavenMatch = MAVEN_RANGE_REGEX.matchEntire(raw.trim())
            if (mavenMatch != null) {
                val lower = PluginVersion.parse(mavenMatch.groupValues[1].trim())
                val upper = PluginVersion.parse(mavenMatch.groupValues[2].trim())
                return version >= lower && version <= upper
            }
            // Support ">=X.Y.Z, <A.B.C" style (comma-separated conditions)
            val conditions = raw.split(',').map { it.trim() }
            return conditions.all { cond -> checkCondition(cond.trim(), version) }
        }

        private fun checkCondition(
            cond: String,
            v: PluginVersion,
        ): Boolean =
            when {
                cond.startsWith(">=") -> v >= PluginVersion.parse(cond.removePrefix(">=").trim())
                cond.startsWith(">") -> v > PluginVersion.parse(cond.removePrefix(">").trim())
                cond.startsWith("<=") -> v <= PluginVersion.parse(cond.removePrefix("<=").trim())
                cond.startsWith("<") -> v < PluginVersion.parse(cond.removePrefix("<").trim())
                cond.startsWith("=") -> v == PluginVersion.parse(cond.removePrefix("=").trim())
                else -> throw IllegalArgumentException("Unrecognised range condition: '$cond'")
            }
    }
}
