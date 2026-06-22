package dev.kuml.io.svg

import dev.kuml.bpmn.model.MessageFlow
import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.c4.model.C4Relationship
import dev.kuml.core.model.KumlElement
import dev.kuml.io.svg.bpmn.edge.renderBpmnMessageFlow
import dev.kuml.io.svg.bpmn.edge.renderBpmnSequenceFlow
import dev.kuml.io.svg.c4.renderC4Relationship
import dev.kuml.io.svg.uml.renderUmlActivityEdge
import dev.kuml.io.svg.uml.renderUmlAssociation
import dev.kuml.io.svg.uml.renderUmlConnector
import dev.kuml.io.svg.uml.renderUmlDependency
import dev.kuml.io.svg.uml.renderUmlExtend
import dev.kuml.io.svg.uml.renderUmlGeneralization
import dev.kuml.io.svg.uml.renderUmlInclude
import dev.kuml.io.svg.uml.renderUmlInterfaceRealization
import dev.kuml.io.svg.uml.renderUmlLink
import dev.kuml.layout.EdgeRoute
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlActivityEdge
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlLink

/**
 * Leitet eine Relationship an den passenden Edge-SVG-Builder weiter.
 *
 * Beispiel:
 * ```kotlin
 * EdgeRendererDispatcher.dispatch(relationship, route, theme, builder)
 * ```
 */
internal object EdgeRendererDispatcher {
    /**
     * Gibt den Simple-Namen der Relationship zurück — für Dispatcher-Tests.
     */
    fun dispatchKey(element: KumlElement): String = element::class.simpleName ?: "Unknown"

    /** Rendert das passende SVG-Fragment für [relationship]. */
    fun dispatch(
        relationship: KumlElement,
        route: EdgeRoute,
        theme: KumlTheme,
        builder: SvgBuilder,
    ) {
        when (relationship) {
            is UmlAssociation -> renderUmlAssociation(relationship, route, theme, builder)
            is UmlGeneralization -> renderUmlGeneralization(relationship, route, theme, builder)
            is UmlInterfaceRealization -> renderUmlInterfaceRealization(relationship, route, theme, builder)
            is UmlDependency -> renderUmlDependency(relationship, route, theme, builder)
            is UmlConnector -> renderUmlConnector(relationship, route, theme, builder)
            is UmlInclude -> renderUmlInclude(relationship, route, theme, builder)
            is UmlExtend -> renderUmlExtend(relationship, route, theme, builder)
            is UmlLink -> renderUmlLink(relationship, route, theme, builder)
            is UmlActivityEdge -> renderUmlActivityEdge(relationship, route, theme, builder)
            is C4Relationship -> renderC4Relationship(relationship, route, theme, builder)
            // BPMN — V3.1.3
            is SequenceFlow -> renderBpmnSequenceFlow(relationship, route, builder)
            // BPMN — V3.1.5 Collaboration
            is MessageFlow -> renderBpmnMessageFlow(relationship, route, builder)
            else -> renderFallbackEdge(route, builder)
        }
    }

    private fun renderFallbackEdge(
        route: EdgeRoute,
        builder: SvgBuilder,
    ) {
        val (tagName, attrs) = EdgePathBuilder.build(route)
        builder.tag(tagName, attrs + mapOf("class" to "kuml-edge"))
    }
}
