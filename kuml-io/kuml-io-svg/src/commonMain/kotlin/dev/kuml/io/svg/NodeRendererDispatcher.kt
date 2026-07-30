package dev.kuml.io.svg

import dev.kuml.bpmn.model.BpmnCallActivity
import dev.kuml.bpmn.model.BpmnDataObject
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnLane
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4DeploymentNode
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.core.model.KumlElement
import dev.kuml.io.svg.bpmn.renderBpmnCallActivity
import dev.kuml.io.svg.bpmn.renderBpmnDataObject
import dev.kuml.io.svg.bpmn.renderBpmnEvent
import dev.kuml.io.svg.bpmn.renderBpmnGateway
import dev.kuml.io.svg.bpmn.renderBpmnLane
import dev.kuml.io.svg.bpmn.renderBpmnParticipant
import dev.kuml.io.svg.bpmn.renderBpmnSubProcess
import dev.kuml.io.svg.bpmn.renderBpmnTask
import dev.kuml.io.svg.c4.renderC4Component
import dev.kuml.io.svg.c4.renderC4Container
import dev.kuml.io.svg.c4.renderC4DeploymentNode
import dev.kuml.io.svg.c4.renderC4Person
import dev.kuml.io.svg.c4.renderC4SoftwareSystem
import dev.kuml.io.svg.sysml2.renderSysml2Definition
import dev.kuml.io.svg.sysml2.renderSysml2Usage
import dev.kuml.io.svg.uml.renderUmlActivityNode
import dev.kuml.io.svg.uml.renderUmlActor
import dev.kuml.io.svg.uml.renderUmlArtifact
import dev.kuml.io.svg.uml.renderUmlClass
import dev.kuml.io.svg.uml.renderUmlCollaboration
import dev.kuml.io.svg.uml.renderUmlComment
import dev.kuml.io.svg.uml.renderUmlComponent
import dev.kuml.io.svg.uml.renderUmlEnum
import dev.kuml.io.svg.uml.renderUmlFinalState
import dev.kuml.io.svg.uml.renderUmlInstance
import dev.kuml.io.svg.uml.renderUmlInteractionOverviewFrame
import dev.kuml.io.svg.uml.renderUmlInterface
import dev.kuml.io.svg.uml.renderUmlLifelineHead
import dev.kuml.io.svg.uml.renderUmlNode
import dev.kuml.io.svg.uml.renderUmlPseudostate
import dev.kuml.io.svg.uml.renderUmlState
import dev.kuml.io.svg.uml.renderUmlStateMachine
import dev.kuml.io.svg.uml.renderUmlStereotype
import dev.kuml.io.svg.uml.renderUmlTimingLifeline
import dev.kuml.io.svg.uml.renderUmlUseCase
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.Sysml2Usage
import dev.kuml.uml.UmlActivityNode
import dev.kuml.uml.UmlActor
import dev.kuml.uml.UmlArtifact
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlCollaboration
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlFinalState
import dev.kuml.uml.UmlInstanceSpecification
import dev.kuml.uml.UmlInteractionOverviewFrame
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlLifeline
import dev.kuml.uml.UmlNode
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlStereotype
import dev.kuml.uml.UmlTimingLifeline
import dev.kuml.uml.UmlUseCase

/**
 * Leitet ein [KumlElement] an den passenden SVG-Builder weiter.
 *
 * Zwei Einstiegspunkte:
 * - [dispatch] — schreibt SVG-Markup in einen [SvgBuilder].
 * - [dispatchKey] — gibt den Klassen-SimpleNamen zurück; für Tests ohne SVG-Output.
 *
 * **Blueprint-Elemente werden hier nicht geroutet.** Das Blueprint-Rendering
 * (`BlueprintPhaseHeaderSvg`, `BlueprintStepSvg`, usw.) schreibt SVG direkt
 * in einen eigenen [SvgBuilder]-Kontext innerhalb von `KumlSvgRenderer.toSvg(BlueprintModel, …)`.
 * Der Dispatcher wird für Blueprint-Renderpfade nie aufgerufen — dieses Bypass-Design
 * ist absichtlich, weil Blueprint-Elemente (Phase, Step, Touchpoint) keine
 * [KumlElement]-Subtypen sind und daher nicht über den gemeinsamen Dispatching-Mechanismus
 * laufen können. Sollte ein zukünftiges Refactoring alle Elemente durch diesen Dispatcher
 * leiten, würden Blueprint-Typen ohne Fehler in den `else`-Zweig (`renderFallbackNode`)
 * fallen und stumm leere Rechtecke erzeugen — ein solches Refactoring muss deshalb
 * explizit einen Blueprint-Arm oder eine Guard-Exception hinzufügen.
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
            is UmlClass -> renderUmlClass(element = element, layout = layout, theme = theme, builder = builder)
            is UmlComment -> renderUmlComment(element = element, layout = layout, theme = theme, builder = builder)
            is UmlInterface -> renderUmlInterface(element = element, layout = layout, theme = theme, builder = builder)
            is UmlEnumeration -> renderUmlEnum(element = element, layout = layout, theme = theme, builder = builder)
            is UmlComponent -> renderUmlComponent(element = element, layout = layout, theme = theme, builder = builder)
            is UmlActor -> renderUmlActor(element = element, layout = layout, theme = theme, builder = builder)
            is UmlUseCase -> renderUmlUseCase(element = element, layout = layout, theme = theme, builder = builder)
            is UmlCollaboration -> renderUmlCollaboration(element = element, layout = layout, theme = theme, builder = builder)
            is UmlStateMachine -> renderUmlStateMachine(element = element, layout = layout, theme = theme, builder = builder)
            is UmlState -> renderUmlState(element = element, layout = layout, theme = theme, builder = builder)
            is UmlPseudostate -> renderUmlPseudostate(element = element, layout = layout, theme = theme, builder = builder)
            is UmlFinalState -> renderUmlFinalState(element = element, layout = layout, theme = theme, builder = builder)
            is UmlInstanceSpecification -> renderUmlInstance(element = element, layout = layout, theme = theme, builder = builder)
            is UmlNode -> renderUmlNode(element = element, layout = layout, theme = theme, builder = builder)
            is UmlArtifact -> renderUmlArtifact(element = element, layout = layout, theme = theme, builder = builder)
            is UmlStereotype -> renderUmlStereotype(element = element, layout = layout, theme = theme, builder = builder)
            is UmlActivityNode -> renderUmlActivityNode(element = element, layout = layout, theme = theme, builder = builder)
            is UmlTimingLifeline -> renderUmlTimingLifeline(element = element, layout = layout, theme = theme, builder = builder)
            is UmlLifeline -> renderUmlLifelineHead(element = element, layout = layout, theme = theme, builder = builder)
            is UmlInteractionOverviewFrame ->
                renderUmlInteractionOverviewFrame(
                    element = element,
                    layout = layout,
                    theme = theme,
                    builder = builder,
                )
            is C4Person -> renderC4Person(element = element, layout = layout, theme = theme, builder = builder)
            is C4SoftwareSystem -> renderC4SoftwareSystem(element = element, layout = layout, theme = theme, builder = builder)
            is C4Container -> renderC4Container(element = element, layout = layout, theme = theme, builder = builder)
            is C4Component -> renderC4Component(element = element, layout = layout, theme = theme, builder = builder)
            is C4DeploymentNode -> renderC4DeploymentNode(element = element, layout = layout, theme = theme, builder = builder)
            is Sysml2Definition -> renderSysml2Definition(element = element, layout = layout, theme = theme, builder = builder)
            is Sysml2Usage -> renderSysml2Usage(element = element, layout = layout, theme = theme, builder = builder)
            // BPMN — V3.1.3
            is BpmnEvent -> renderBpmnEvent(event = element, layout = layout, theme = theme, builder = builder)
            is BpmnGateway -> renderBpmnGateway(gw = element, layout = layout, theme = theme, builder = builder)
            is BpmnTask -> renderBpmnTask(task = element, layout = layout, theme = theme, builder = builder)
            is BpmnSubProcess -> renderBpmnSubProcess(sp = element, layout = layout, theme = theme, builder = builder)
            is BpmnCallActivity -> renderBpmnCallActivity(ca = element, layout = layout, theme = theme, builder = builder)
            is BpmnDataObject -> renderBpmnDataObject(data = element, layout = layout, theme = theme, builder = builder)
            // BPMN — V3.1.4 Collaboration
            is BpmnParticipant -> renderBpmnParticipant(participant = element, layout = layout, theme = theme, builder = builder)
            is BpmnLane -> renderBpmnLane(lane = element, layout = layout, horizontal = true, theme = theme, builder = builder)
            else -> renderFallbackNode(element = element, layout = layout, builder = builder)
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
            name = "g",
            attrs = mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
        ) {
            tag(name = "rect", attrs = mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-class"))
            tag(
                name = "text",
                attrs = mapOf("class" to "kuml-body", "x" to fmt(w / 2f), "y" to "20", "text-anchor" to "middle"),
            ) {
                text(element.id)
            }
        }
    }

    private fun fmt(v: Float): String = fmt2(v)
}
