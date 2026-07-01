package dev.kuml.c4.model

import dev.kuml.core.model.KumlElement
import kotlinx.serialization.Serializable

/**
 * Sealed interface for all C4 model elements.
 *
 * Exhaustive `when` expressions are possible when processing C4 models.
 * Extends the open [KumlElement] core interface from kuml-core-model.
 */
@Serializable
sealed interface C4Element : KumlElement {
    val name: String
    val description: String?
}

// ── Type alias for element IDs ────────────────────────────────────────────

typealias ElementId = String
