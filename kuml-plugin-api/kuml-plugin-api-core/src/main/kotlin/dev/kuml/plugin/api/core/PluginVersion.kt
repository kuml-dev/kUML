package dev.kuml.plugin.api.core

import kotlinx.serialization.Serializable

@Serializable
public data class PluginVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<PluginVersion> {
    override fun compareTo(other: PluginVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    public companion object {
        public fun parse(s: String): PluginVersion {
            val parts = s.split('.').map { it.toIntOrNull() ?: 0 }
            return PluginVersion(
                parts.getOrElse(0) { 0 },
                parts.getOrElse(1) { 0 },
                parts.getOrElse(2) { 0 },
            )
        }
    }
}
