package dev.kuml.renderer.kuiver

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import com.dk.kuiver.model.KuiverEdge
import dev.kuml.c4.model.C4Relationship
import dev.kuml.core.model.KumlElement
import dev.kuml.renderer.kuiver.c4.C4RelationshipEdge
import dev.kuml.renderer.kuiver.uml.AssociationEdge
import dev.kuml.renderer.kuiver.uml.ConnectorEdge
import dev.kuml.renderer.kuiver.uml.DependencyEdge
import dev.kuml.renderer.kuiver.uml.ExtendEdge
import dev.kuml.renderer.kuiver.uml.GeneralizationEdge
import dev.kuml.renderer.kuiver.uml.IncludeEdge
import dev.kuml.renderer.kuiver.uml.InterfaceRealizationEdge
import dev.kuml.renderer.theme.KumlTheme
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlInterfaceRealization

/**
 * Routes a relationship [KumlElement] to the matching edge Composable.
 *
 * Like [NodeContentDispatcher], a plain-Kotlin [dispatchKey] function is provided
 * for testing without Compose.
 *
 * The [render] function receives the resolved source/target [Offset] values from
 * `KuiverViewer`'s `edgeContent` lambda.
 *
 * Covered relationship kinds:
 * - UML: [UmlAssociation], [UmlGeneralization], [UmlInterfaceRealization],
 *   [UmlDependency], [UmlConnector], [UmlInclude], [UmlExtend]
 * - C4: [C4Relationship]
 *
 * Example:
 * ```kotlin
 * EdgeContentDispatcher.dispatchKey(UmlGeneralization(…)) shouldBe "UmlGeneralization"
 * ```
 */
internal object EdgeContentDispatcher {
    /**
     * Returns the simple class name of the incoming relationship — used in tests
     * without Compose runtime.
     */
    internal fun dispatchKey(element: KumlElement): String = element::class.simpleName ?: "Unknown"

    /**
     * Renders the edge Composable matching [relationship].
     *
     * [kuiverEdge] carries anchor information from Kuiver.
     * [source] and [target] are the resolved absolute screen coordinates.
     */
    @Composable
    internal fun render(
        relationship: KumlElement,
        kuiverEdge: KuiverEdge,
        source: Offset,
        target: Offset,
        theme: KumlTheme,
    ) {
        when (relationship) {
            is UmlAssociation -> AssociationEdge(relationship, source, target, theme)
            is UmlGeneralization -> GeneralizationEdge(relationship, source, target, theme)
            is UmlInterfaceRealization -> InterfaceRealizationEdge(relationship, source, target, theme)
            is UmlDependency -> DependencyEdge(relationship, source, target, theme)
            is UmlConnector -> ConnectorEdge(relationship, source, target, theme)
            is UmlInclude -> IncludeEdge(relationship, source, target, theme)
            is UmlExtend -> ExtendEdge(relationship, source, target, theme)
            is C4Relationship -> C4RelationshipEdge(relationship, source, target, theme)
            else -> GenericFallbackEdge(source, target, theme)
        }
    }
}
