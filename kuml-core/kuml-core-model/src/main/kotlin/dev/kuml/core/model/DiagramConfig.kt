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

/**
 * Display options for a UML component diagram.
 *
 * @property showPortLabels Display port names next to their squares.
 * @property showInterfaceContracts Render provided/required interfaces as ball-and-socket symbols.
 * @property showNestedComponents Render nested components inside their owner's boundary.
 * @property showStereotype Always render the «component» keyword.
 */
@Serializable
data class ComponentDiagramConfig(
    val showPortLabels: Boolean = true,
    val showInterfaceContracts: Boolean = true,
    val showNestedComponents: Boolean = true,
    val showStereotype: Boolean = true,
) : DiagramConfig

/**
 * Display options for a UML state-machine diagram.
 *
 * @property showGuards Render `[guard]` labels on transition arrows.
 * @property showEffects Render `/effect` labels on transition arrows.
 * @property showEntryExitActions Display entry/exit/do compartments on state nodes.
 * @property orientation Top-down vs. left-right layout hint.
 */
@Serializable
data class StateDiagramConfig(
    val showGuards: Boolean = true,
    val showEffects: Boolean = true,
    val showEntryExitActions: Boolean = true,
    val orientation: StateDiagramOrientation = StateDiagramOrientation.TOP_DOWN,
) : DiagramConfig

@Serializable
enum class StateDiagramOrientation { TOP_DOWN, LEFT_RIGHT }

/**
 * Display options for a UML sequence diagram.
 *
 * @property showActivationBars Render activation rectangles on lifelines for sync calls.
 * @property showSequenceNumbers Prefix message labels with their sequence number.
 * @property showReturnArrows Always draw explicit dashed return arrows for replies.
 * @property numberFragmentBranches Number ALT/PAR operands with `1:`, `2:` …
 */
@Serializable
data class SequenceDiagramConfig(
    val showActivationBars: Boolean = true,
    val showSequenceNumbers: Boolean = false,
    val showReturnArrows: Boolean = true,
    val numberFragmentBranches: Boolean = true,
) : DiagramConfig

/**
 * Display options for a UML object diagram (V1.1).
 *
 * @property showClassifierType Render the `: ClassifierName` after each instance name.
 * @property showSlotCompartment Include the slot/value compartment under the header.
 * @property showNullSlots Render slots whose value is [UmlInstanceValue.Null]. When
 *   `false`, those slots are silently omitted.
 */
@Serializable
data class ObjectDiagramConfig(
    val showClassifierType: Boolean = true,
    val showSlotCompartment: Boolean = true,
    val showNullSlots: Boolean = true,
) : DiagramConfig
