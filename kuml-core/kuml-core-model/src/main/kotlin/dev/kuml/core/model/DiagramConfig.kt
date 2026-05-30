package dev.kuml.core.model

import kotlinx.serialization.Serializable

/** Type-safe display configuration attached to a [KumlDiagram]. */
@Serializable
sealed interface DiagramConfig

/**
 * Display options for a UML class diagram.
 *
 * These settings influence the renderer — they have no effect on validation or code generation.
 *
 * @property showAttributes Include attribute compartment for each classifier.
 * @property showOperations Include operation compartment for each classifier.
 * @property showVisibility Prefix each attribute / operation with its visibility symbol.
 * @property visibilityFilter Restrict which classifiers appear based on their visibility.
 * @property showPackageNames Prefix classifier names with their owning package.
 */
@Serializable
data class ClassDiagramConfig(
    val showAttributes: Boolean = true,
    val showOperations: Boolean = true,
    val showVisibility: Boolean = true,
    val visibilityFilter: VisibilityFilter = VisibilityFilter.ALL,
    val showPackageNames: Boolean = false,
) : DiagramConfig

/** Which classifiers are included in the rendered output based on their visibility. */
enum class VisibilityFilter { ALL, PUBLIC_AND_PROTECTED, PUBLIC_ONLY }

/**
 * Display options for a UML use-case diagram.
 *
 * @property showSubjectBox Render the subject as a labeled boundary rectangle.
 * @property actorStyle Stick-figure vs. rectangle-with-stereotype.
 */
@Serializable
data class UseCaseDiagramConfig(
    val showSubjectBox: Boolean = true,
    val actorStyle: ActorStyle = ActorStyle.STICK_FIGURE,
) : DiagramConfig

@Serializable
enum class ActorStyle { STICK_FIGURE, RECTANGLE }
