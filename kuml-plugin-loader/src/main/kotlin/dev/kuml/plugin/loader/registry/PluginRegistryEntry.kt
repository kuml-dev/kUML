package dev.kuml.plugin.loader.registry

import kotlinx.serialization.Serializable

/**
 * One entry in the `plugins/index.json` registry index.
 *
 * Published at `https://plugins.kuml.dev/plugins/index.json` (V3.0.30).
 */
@Serializable
public data class PluginRegistryEntry(
    val id: String,
    val category: String,
    val name: String,
    val version: String,
    val kumlVersionRange: String = "",
    val manifest: String,
    val downloads: String,
    val signaturePublicKey: String? = null,
    val maintainer: String = "",
    val homepage: String = "",
)
