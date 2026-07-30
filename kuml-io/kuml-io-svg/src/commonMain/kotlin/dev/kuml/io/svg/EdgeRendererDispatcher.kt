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
import dev.kuml.io.svg.uml.renderUmlCommentLink
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
import dev.kuml.uml.UmlCommentLink
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

    /**
     * Rendert das passende SVG-Fragment für [relationship].
     *
     * [sourceStackIndex] / [targetStackIndex] (fix/uml-association-label-
     * overlap) are forwarded only to the two label-bearing UML relationship
     * kinds that support converging-endpoint fan-out — [UmlAssociation] and
     * [UmlLink]. All other branches ignore them; see
     * `dev.kuml.io.svg.uml.renderUmlAssociation`'s KDoc for the rationale.
     */
    fun dispatch(
        relationship: KumlElement,
        route: EdgeRoute,
        theme: KumlTheme,
        builder: SvgBuilder,
        sourceStackIndex: Int = 0,
        targetStackIndex: Int = 0,
    ) {
        when (relationship) {
            is UmlAssociation ->
                renderUmlAssociation(
                    rel = relationship,
                    route = route,
                    theme = theme,
                    builder = builder,
                    sourceStackIndex = sourceStackIndex,
                    targetStackIndex = targetStackIndex,
                )
            is UmlGeneralization -> renderUmlGeneralization(rel = relationship, route = route, theme = theme, builder = builder)
            is UmlInterfaceRealization -> renderUmlInterfaceRealization(rel = relationship, route = route, theme = theme, builder = builder)
            is UmlDependency -> renderUmlDependency(rel = relationship, route = route, theme = theme, builder = builder)
            is UmlConnector -> renderUmlConnector(rel = relationship, route = route, theme = theme, builder = builder)
            is UmlInclude -> renderUmlInclude(rel = relationship, route = route, theme = theme, builder = builder)
            is UmlExtend -> renderUmlExtend(rel = relationship, route = route, theme = theme, builder = builder)
            is UmlLink ->
                renderUmlLink(
                    rel = relationship,
                    route = route,
                    theme = theme,
                    builder = builder,
                    sourceStackIndex = sourceStackIndex,
                    targetStackIndex = targetStackIndex,
                )
            is UmlActivityEdge -> renderUmlActivityEdge(rel = relationship, route = route, theme = theme, builder = builder)
            is UmlCommentLink -> renderUmlCommentLink(route = route, builder = builder)
            is C4Relationship -> renderC4Relationship(rel = relationship, route = route, theme = theme, builder = builder)
            // BPMN — V3.1.3
            is SequenceFlow -> renderBpmnSequenceFlow(flow = relationship, route = route, builder = builder, theme = theme)
            // BPMN — V3.1.5 Collaboration
            is MessageFlow -> renderBpmnMessageFlow(flow = relationship, route = route, builder = builder, theme = theme)
            else -> renderFallbackEdge(route = route, builder = builder)
        }
    }

    private fun renderFallbackEdge(
        route: EdgeRoute,
        builder: SvgBuilder,
    ) {
        val (tagName, attrs) = EdgePathBuilder.build(route)
        builder.tag(name = tagName, attrs = attrs + mapOf("class" to "kuml-edge"))
    }
}
