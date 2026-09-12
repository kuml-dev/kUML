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
import dev.kuml.uml.UmlAssociationClass
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
     * Rendert das passende SVG-Fragment für [relationship], gewrappt in ein
     * `<g id="…">` (fix/edge-svg-element-ids) so downstream DOM consumers
     * (e.g. kUML Portal's click-to-select) can address the rendered edge the
     * same way node renderers already let them address nodes.
     *
     * [sourceStackIndex] / [targetStackIndex] (fix/uml-association-label-
     * overlap) are forwarded only to the two label-bearing UML relationship
     * kinds that support converging-endpoint fan-out — [UmlAssociation] and
     * [UmlLink]. All other branches ignore them; see
     * `dev.kuml.io.svg.uml.renderUmlAssociation`'s KDoc for the rationale.
     *
     * **Pre-existing id-collision risk (not introduced by this fix):** if the
     * diagram DSL assigns a node and a relationship the same explicit custom
     * `id`, both wrappers end up as `<g id="…">` with an identical value —
     * an invalid SVG document (duplicate ids). This risk already existed
     * between two nodes with the same custom id before edges carried an id
     * at all; giving edges an id merely widens the surface it's visible on.
     * There is no `KUML-E-xxx` duplicate-id validator yet (see
     * `UmlIds.kt`'s KDoc) — tracked as a follow-up, out of scope here.
     */
    fun dispatch(
        relationship: KumlElement,
        route: EdgeRoute,
        theme: KumlTheme,
        builder: SvgBuilder,
        sourceStackIndex: Int = 0,
        targetStackIndex: Int = 0,
    ) {
        builder.tag(name = "g", attrs = mapOf("id" to xmlEscapeAttr(relationship.id))) {
            when (relationship) {
                is UmlAssociation ->
                    renderUmlAssociation(
                        rel = relationship,
                        route = route,
                        theme = theme,
                        builder = this,
                        sourceStackIndex = sourceStackIndex,
                        targetStackIndex = targetStackIndex,
                    )
                is UmlAssociationClass ->
                    renderUmlAssociation(
                        rel = relationship,
                        route = route,
                        theme = theme,
                        builder = this,
                        sourceStackIndex = sourceStackIndex,
                        targetStackIndex = targetStackIndex,
                    )
                is UmlGeneralization -> renderUmlGeneralization(rel = relationship, route = route, theme = theme, builder = this)
                is UmlInterfaceRealization ->
                    renderUmlInterfaceRealization(
                        rel = relationship,
                        route = route,
                        theme = theme,
                        builder = this,
                    )
                is UmlDependency -> renderUmlDependency(rel = relationship, route = route, theme = theme, builder = this)
                is UmlConnector -> renderUmlConnector(rel = relationship, route = route, theme = theme, builder = this)
                is UmlInclude -> renderUmlInclude(rel = relationship, route = route, theme = theme, builder = this)
                is UmlExtend -> renderUmlExtend(rel = relationship, route = route, theme = theme, builder = this)
                is UmlLink ->
                    renderUmlLink(
                        rel = relationship,
                        route = route,
                        theme = theme,
                        builder = this,
                        sourceStackIndex = sourceStackIndex,
                        targetStackIndex = targetStackIndex,
                    )
                is UmlActivityEdge -> renderUmlActivityEdge(rel = relationship, route = route, theme = theme, builder = this)
                is UmlCommentLink -> renderUmlCommentLink(route = route, builder = this)
                is C4Relationship -> renderC4Relationship(rel = relationship, route = route, theme = theme, builder = this)
                // BPMN — V3.1.3
                is SequenceFlow -> renderBpmnSequenceFlow(flow = relationship, route = route, builder = this, theme = theme)
                // BPMN — V3.1.5 Collaboration
                is MessageFlow -> renderBpmnMessageFlow(flow = relationship, route = route, builder = this, theme = theme)
                else -> renderFallbackEdge(route = route, builder = this)
            }
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
