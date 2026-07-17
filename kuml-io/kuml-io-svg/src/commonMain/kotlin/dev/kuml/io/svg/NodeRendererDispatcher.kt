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
            is UmlClass -> renderUmlClass(element, layout, theme, builder)
            is UmlComment -> renderUmlComment(element, layout, theme, builder)
            is UmlInterface -> renderUmlInterface(element, layout, theme, builder)
            is UmlEnumeration -> renderUmlEnum(element, layout, theme, builder)
            is UmlComponent -> renderUmlComponent(element, layout, theme, builder)
            is UmlActor -> renderUmlActor(element, layout, theme, builder)
            is UmlUseCase -> renderUmlUseCase(element, layout, theme, builder)
            is UmlCollaboration -> renderUmlCollaboration(element, layout, theme, builder)
            is UmlStateMachine -> renderUmlStateMachine(element, layout, theme, builder)
            is UmlState -> renderUmlState(element, layout, theme, builder)
            is UmlPseudostate -> renderUmlPseudostate(element, layout, theme, builder)
            is UmlFinalState -> renderUmlFinalState(element, layout, theme, builder)
            is UmlInstanceSpecification -> renderUmlInstance(element, layout, theme, builder)
            is UmlNode -> renderUmlNode(element, layout, theme, builder)
            is UmlArtifact -> renderUmlArtifact(element, layout, theme, builder)
            is UmlStereotype -> renderUmlStereotype(element, layout, theme, builder)
            is UmlActivityNode -> renderUmlActivityNode(element, layout, theme, builder)
            is UmlTimingLifeline -> renderUmlTimingLifeline(element, layout, theme, builder)
            is UmlLifeline -> renderUmlLifelineHead(element, layout, theme, builder)
            is UmlInteractionOverviewFrame -> renderUmlInteractionOverviewFrame(element, layout, theme, builder)
            is C4Person -> renderC4Person(element, layout, theme, builder)
            is C4SoftwareSystem -> renderC4SoftwareSystem(element, layout, theme, builder)
            is C4Container -> renderC4Container(element, layout, theme, builder)
            is C4Component -> renderC4Component(element, layout, theme, builder)
            is C4DeploymentNode -> renderC4DeploymentNode(element, layout, theme, builder)
            is Sysml2Definition -> renderSysml2Definition(element, layout, theme, builder)
            is Sysml2Usage -> renderSysml2Usage(element, layout, theme, builder)
            // BPMN — V3.1.3
            is BpmnEvent -> renderBpmnEvent(element, layout, theme, builder)
            is BpmnGateway -> renderBpmnGateway(element, layout, theme, builder)
            is BpmnTask -> renderBpmnTask(element, layout, theme, builder)
            is BpmnSubProcess -> renderBpmnSubProcess(element, layout, theme, builder)
            is BpmnCallActivity -> renderBpmnCallActivity(element, layout, theme, builder)
            is BpmnDataObject -> renderBpmnDataObject(element, layout, theme, builder)
            // BPMN — V3.1.4 Collaboration
            is BpmnParticipant -> renderBpmnParticipant(element, layout, theme, builder)
            is BpmnLane -> renderBpmnLane(element, layout, horizontal = true, theme, builder)
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

    private fun fmt(v: Float): String = fmt2(v)
}
