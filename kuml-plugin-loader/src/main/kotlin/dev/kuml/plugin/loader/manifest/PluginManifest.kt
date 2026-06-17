package dev.kuml.plugin.loader.manifest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Parsed representation of a `kuml-plugin.json` manifest.
 *
 * Schema version 1. All fields except [maintainer], [homepage],
 * [licenseSpdx], and [signature] are required.
 */
@Serializable
public data class PluginManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val version: String,
    val kumlVersionRange: String,
    val extensions: List<ExtensionEntry>,
    val permissions: List<String> = emptyList(),
    val maintainer: String = "",
    val homepage: String = "",
    @SerialName("licenseSpdx") val licenseSpdx: String = "Apache-2.0",
    val signature: String? = null,
)
