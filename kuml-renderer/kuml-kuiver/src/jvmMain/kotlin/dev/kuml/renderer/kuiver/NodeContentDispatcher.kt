package dev.kuml.renderer.kuiver

import androidx.compose.runtime.Composable
import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.core.model.KumlElement
import dev.kuml.renderer.kuiver.c4.C4ComponentNode
import dev.kuml.renderer.kuiver.c4.C4ContainerNode
import dev.kuml.renderer.kuiver.c4.C4PersonNode
import dev.kuml.renderer.kuiver.c4.C4SoftwareSystemNode
import dev.kuml.renderer.kuiver.uml.UmlActorNode
import dev.kuml.renderer.kuiver.uml.UmlClassNode
import dev.kuml.renderer.kuiver.uml.UmlComponentNode
import dev.kuml.renderer.kuiver.uml.UmlEnumNode
import dev.kuml.renderer.kuiver.uml.UmlInterfaceNode
import dev.kuml.renderer.kuiver.uml.UmlStateNode
import dev.kuml.renderer.kuiver.uml.UmlUseCaseNode
import dev.kuml.renderer.theme.KumlTheme
import dev.kuml.uml.UmlActor
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlUseCase

/**
 * Routes a [KumlElement] to the matching node Composable.
 *
 * Two entry points are provided:
 * - [render] — Compose-Composable; used by the live renderer.
 * - [dispatchKey] — plain-Kotlin; returns the `simpleName` of the expected
 *   Composable. This is the testable surface used by `NodeContentDispatcherTest`.
 *
 * Example:
 * ```kotlin
 * // In a @Composable context:
 * NodeContentDispatcher.render(element, PlainTheme)
 *
 * // In a plain test:
 * NodeContentDispatcher.dispatchKey(UmlClass(…)) shouldBe "UmlClass"
 * ```
 */
internal object NodeContentDispatcher {

    /**
     * Returns the simple class name of the incoming element — used in tests to
     * verify that every element type has a dispatch path without running Compose.
     */
    internal fun dispatchKey(element: KumlElement): String =
        element::class.simpleName ?: "Unknown"

    /** Renders the appropriate node Composable for [element]. */
    @Composable
    internal fun render(element: KumlElement, theme: KumlTheme) {
        when (element) {
            is UmlClass -> UmlClassNode(element, theme)
            is UmlInterface -> UmlInterfaceNode(element, theme)
            is UmlEnumeration -> UmlEnumNode(element, theme)
            is UmlComponent -> UmlComponentNode(element, theme)
            is UmlActor -> UmlActorNode(element, theme)
            is UmlUseCase -> UmlUseCaseNode(element, theme)
            is UmlState -> UmlStateNode(element, theme)
            is C4Person -> C4PersonNode(element, theme)
            is C4SoftwareSystem -> C4SoftwareSystemNode(element, theme)
            is C4Container -> C4ContainerNode(element, theme)
            is C4Component -> C4ComponentNode(element, theme)
            else -> GenericFallbackNode(element, theme)
        }
    }
}
