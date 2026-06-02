package dev.kuml.io.svg

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.core.model.KumlElement
import dev.kuml.io.svg.c4.renderC4Component
import dev.kuml.io.svg.c4.renderC4Container
import dev.kuml.io.svg.c4.renderC4Person
import dev.kuml.io.svg.c4.renderC4SoftwareSystem
import dev.kuml.io.svg.uml.renderUmlActor
import dev.kuml.io.svg.uml.renderUmlClass
import dev.kuml.io.svg.uml.renderUmlComponent
import dev.kuml.io.svg.uml.renderUmlEnum
import dev.kuml.io.svg.uml.renderUmlInterface
import dev.kuml.io.svg.uml.renderUmlState
import dev.kuml.io.svg.uml.renderUmlUseCase
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlActor
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlUseCase

/**
 * Leitet ein [KumlElement] an den passenden SVG-Builder weiter.
 *
 * Zwei Einstiegspunkte:
 * - [dispatch] — schreibt SVG-Markup in einen [SvgBuilder].
 * - [dispatchKey] — gibt den Klassen-SimpleNamen zurück; für Tests ohne SVG-Output.
 *
 * Beispiel:
 * ```kotlin
 * NodeRendererDispatcher.dispatch(element, layout, theme, builder)
 * ```
 */
internal object NodeRendererDispatcher {
    /**
     * Gibt den Simple-Namen des Elements zurück — für Dispatcher-Tests ohne Render-Lauf.
     */
    fun dispatchKey(element: KumlElement): String = element::class.simpleName ?: "Unknown"

    /** Rendert das passende SVG-Fragment für [element]. */
    fun dispatch(
        element: KumlElement,
        layout: NodeLayout,
        theme: KumlTheme,
        builder: SvgBuilder,
    ) {
        when (element) {
            is UmlClass -> renderUmlClass(element, layout, theme, builder)
            is UmlInterface -> renderUmlInterface(element, layout, theme, builder)
            is UmlEnumeration -> renderUmlEnum(element, layout, theme, builder)
            is UmlComponent -> renderUmlComponent(element, layout, theme, builder)
            is UmlActor -> renderUmlActor(element, layout, theme, builder)
            is UmlUseCase -> renderUmlUseCase(element, layout, theme, builder)
            is UmlState -> renderUmlState(element, layout, theme, builder)
            is C4Person -> renderC4Person(element, layout, theme, builder)
            is C4SoftwareSystem -> renderC4SoftwareSystem(element, layout, theme, builder)
            is C4Container -> renderC4Container(element, layout, theme, builder)
            is C4Component -> renderC4Component(element, layout, theme, builder)
            else -> renderFallbackNode(element, layout, builder)
        }
    }

    private fun renderFallbackNode(
        element: KumlElement,
        layout: NodeLayout,
        builder: SvgBuilder,
    ) {
        val x = layout.bounds.origin.x
        val y = layout.bounds.origin.y
        val w = layout.bounds.size.width
        val h = layout.bounds.size.height
        builder.tag(
            "g",
            mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
        ) {
            tag("rect", mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-class"))
            tag(
                "text",
                mapOf("class" to "kuml-body", "x" to fmt(w / 2f), "y" to "20", "text-anchor" to "middle"),
            ) {
                text(element.id)
            }
        }
    }

    private fun fmt(v: Float): String {
        val i = v.toInt()
        return if (v == i.toFloat()) "$i" else "%.2f".format(v)
    }
}
