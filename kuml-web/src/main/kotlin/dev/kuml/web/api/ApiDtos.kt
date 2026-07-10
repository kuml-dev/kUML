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

/**
 * Request body for `POST /api/layout/hint` — a drag-and-drop grid placement
 * for a single element in a UML class-diagram script.
 */
@Serializable
data class LayoutHintRequest(
    val script: String,
    val elementId: String,
    val col: Int,
    val row: Int,
)

/**
 * Response body for `POST /api/layout/hint`.
 *
 * On success, [script] carries the re-parseable `.kuml.kts` source with the
 * new grid hint applied — the client re-renders from this script. On
 * failure, [error] carries a human-readable reason (unknown element,
 * relationship target, occupied cell, non-UML-class-diagram script, …).
 */
@Serializable
data class LayoutHintResponse(
    val ok: Boolean,
    val script: String? = null,
    val error: String? = null,
)
