package dev.kuml.c4.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A C4 code-level element representing a code-level building block within a component.
 *
 * Corresponds to the optional fourth (Code) level of the C4 model. A code element is
 * typically a class, interface, or other code construct that is significant enough to
 * appear in an architecture diagram. The C4 Code level is conventionally rendered with
 * UML class diagram notation; this metamodel element captures only the minimum needed
 * to identify the code construct and locate it within its parent component.
 *
 * @property id Unique stable identifier
 * @property name Human-readable name (typically the class or interface name)
 * @property description Optional description
 * @property technology Optional technology or language specification (e.g., "Kotlin", "Java 21")
 * @property component Optional ID of the parent component
 * @property metadata Arbitrary additional metadata
 */
@Serializable
data class C4CodeElement(
    override val id: ElementId,
    override val name: String,
    override val description: String? = null,
    val technology: String? = null,
    val component: ElementId? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : C4Element
