package dev.kuml.web.api

import kotlinx.serialization.Serializable

@Serializable
data class RenderRequest(
    val script: String,
    val format: String = "svg",
    val theme: String? = null,
    val layout: String? = null,
    val widthPx: Int = 1024,
    val standaloneTex: Boolean = false,
    // ERM notation override: martin|bachman|chen|idef1x (case-insensitive); null uses the DSL's declared notation.
    val notation: String? = null,
)

@Serializable
data class RenderResponse(
    val ok: Boolean,
    val format: String = "svg",
    val svg: String? = null,
    val pngBase64: String? = null,
    val latex: String? = null,
    val durationMs: Long = 0,
    val error: String? = null,
)

@Serializable
data class ThemesResponse(
    val themes: List<String>,
)

@Serializable
data class ExampleEntry(
    val name: String,
    val title: String,
)

@Serializable
data class ExamplesResponse(
    val examples: List<ExampleEntry>,
)

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
)
