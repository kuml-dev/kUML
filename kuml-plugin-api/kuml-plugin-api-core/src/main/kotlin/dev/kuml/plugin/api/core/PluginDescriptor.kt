package dev.kuml.plugin.api.core

import kotlinx.serialization.Serializable

/**
 * Immutable metadata record for a kUML plugin.
 *
 * Mirrors the `kuml-plugin.json` manifest — constructed from the manifest
 * by [kuml-plugin-loader] at runtime, or supplied directly by Built-In plugins
 * that don't ship a JSON file.
 */
@Serializable
public data class PluginDescriptor(
    val id: String,
    val name: String,
    val version: PluginVersion,
    val kumlVersionRange: KumlVersionRange,
    val capabilities: Set<PluginCapability>,
    val requiredPermissions: Set<PluginPermission> = emptySet(),
    val maintainer: String = "",
    val homepage: String = "",
    val licenseSpdx: String = "Apache-2.0",
)
