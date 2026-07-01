package dev.kuml.uml

import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.KumlNamespaceMember
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * Registers the concrete UML metamodel element types with the open
 * [KumlElement] / [KumlNamespaceMember] polymorphic bases from
 * `kuml-core-model`.
 *
 * `KumlElement` is intentionally a plain (non-sealed) interface so that
 * independent metamodel modules (UML, C4, BPMN, SysML 2, KerML, Blueprint)
 * can all implement it without being forced into the same module/package.
 * kotlinx.serialization cannot auto-derive a serializer for an open
 * polymorphic type — it needs an explicit runtime registry of "here are the
 * concrete subtypes that exist for this base." This module is that registry
 * for the UML metamodel.
 *
 * Usage:
 * ```kotlin
 * val json = Json {
 *     serializersModule = UmlSerializersModule
 *     classDiscriminator = "@type" // KumlDiagram.type field collides with the default "type" key
 * }
 * val diagram = json.decodeFromString<KumlDiagram>(jsonText)
 * ```
 *
 * Scope: **UML only**. C4/BPMN/SysML2/KerML/Blueprint elements are already
 * `@Serializable` in their respective modules but are NOT registered here —
 * decoding a diagram containing them via this module alone throws
 * `SerializationException`. Each metamodel module is expected to provide its
 * own `<Language>SerializersModule` that callers combine via
 * `SerializersModule { include(...) }` or `plus`, e.g.:
 * `UmlSerializersModule + C4SerializersModule`.
 *
 * Both [KumlElement] and [KumlNamespaceMember] polymorphic bases are
 * registered with the same concrete-subtype set below, because every UML
 * element that is a [KumlNamespaceMember] is transitively also a
 * [KumlElement], and `KumlDiagram.elements` decodes through the
 * [KumlElement] base while nested containers (e.g. `UmlPackage.members`)
 * decode through the narrower [KumlNamespaceMember] base.
 */
public val UmlSerializersModule: SerializersModule =
    SerializersModule {
        polymorphic(KumlElement::class) {
            // Classifiers
            subclass(UmlClass::class, UmlClass.serializer())
            subclass(UmlInterface::class, UmlInterface.serializer())
            subclass(UmlEnumeration::class, UmlEnumeration.serializer())
            subclass(UmlEnumerationLiteral::class, UmlEnumerationLiteral.serializer())
            subclass(UmlPackage::class, UmlPackage.serializer())

            // Collaboration
            subclass(UmlCollaboration::class, UmlCollaboration.serializer())
            subclass(UmlCollaborationRole::class, UmlCollaborationRole.serializer())

            // Components
            subclass(UmlComponent::class, UmlComponent.serializer())
            subclass(UmlPort::class, UmlPort.serializer())
            subclass(UmlConnector::class, UmlConnector.serializer())

            // Features
            subclass(UmlProperty::class, UmlProperty.serializer())
            subclass(UmlOperation::class, UmlOperation.serializer())
            subclass(UmlParameter::class, UmlParameter.serializer())
            subclass(UmlConstraint::class, UmlConstraint.serializer())

            // Interaction (sequence diagrams)
            subclass(UmlInteraction::class, UmlInteraction.serializer())
            subclass(UmlLifeline::class, UmlLifeline.serializer())
            subclass(UmlMessage::class, UmlMessage.serializer())
            subclass(UmlCombinedFragment::class, UmlCombinedFragment.serializer())

            // Instances (object diagrams)
            subclass(UmlInstanceSpecification::class, UmlInstanceSpecification.serializer())
            subclass(UmlLink::class, UmlLink.serializer())

            // State machines
            subclass(UmlStateMachine::class, UmlStateMachine.serializer())
            subclass(UmlState::class, UmlState.serializer())
            subclass(UmlPseudostate::class, UmlPseudostate.serializer())
            subclass(UmlFinalState::class, UmlFinalState.serializer())
            subclass(UmlTransition::class, UmlTransition.serializer())

            // Relationships
            subclass(UmlAssociation::class, UmlAssociation.serializer())
            subclass(UmlGeneralization::class, UmlGeneralization.serializer())
            subclass(UmlInterfaceRealization::class, UmlInterfaceRealization.serializer())
            subclass(UmlDependency::class, UmlDependency.serializer())

            // Use cases
            subclass(UmlActor::class, UmlActor.serializer())
            subclass(UmlUseCase::class, UmlUseCase.serializer())
            subclass(UmlUseCaseSubject::class, UmlUseCaseSubject.serializer())
            subclass(UmlInclude::class, UmlInclude.serializer())
            subclass(UmlExtend::class, UmlExtend.serializer())

            // V1.1 types
            subclass(UmlNode::class, UmlNode.serializer())
            subclass(UmlArtifact::class, UmlArtifact.serializer())
            subclass(UmlStereotype::class, UmlStereotype.serializer())
            subclass(UmlActivityNode::class, UmlActivityNode.serializer())
            subclass(UmlActivityEdge::class, UmlActivityEdge.serializer())
            subclass(UmlTimingLifeline::class, UmlTimingLifeline.serializer())
            subclass(UmlInteractionOverviewFrame::class, UmlInteractionOverviewFrame.serializer())
        }

        polymorphic(KumlNamespaceMember::class) {
            // Only the subset of the above that is also a KumlNamespaceMember
            // (i.e. extends UmlNamedElement, not just UmlElement).
            subclass(UmlClass::class, UmlClass.serializer())
            subclass(UmlInterface::class, UmlInterface.serializer())
            subclass(UmlEnumeration::class, UmlEnumeration.serializer())
            subclass(UmlEnumerationLiteral::class, UmlEnumerationLiteral.serializer())
            subclass(UmlPackage::class, UmlPackage.serializer())
            subclass(UmlCollaboration::class, UmlCollaboration.serializer())
            subclass(UmlCollaborationRole::class, UmlCollaborationRole.serializer())
            subclass(UmlComponent::class, UmlComponent.serializer())
            subclass(UmlPort::class, UmlPort.serializer())
            subclass(UmlProperty::class, UmlProperty.serializer())
            subclass(UmlOperation::class, UmlOperation.serializer())
            subclass(UmlParameter::class, UmlParameter.serializer())
            subclass(UmlConstraint::class, UmlConstraint.serializer())
            subclass(UmlInteraction::class, UmlInteraction.serializer())
            subclass(UmlLifeline::class, UmlLifeline.serializer())
            subclass(UmlInstanceSpecification::class, UmlInstanceSpecification.serializer())
            subclass(UmlStateMachine::class, UmlStateMachine.serializer())
            subclass(UmlState::class, UmlState.serializer())
            subclass(UmlPseudostate::class, UmlPseudostate.serializer())
            subclass(UmlFinalState::class, UmlFinalState.serializer())
            subclass(UmlActor::class, UmlActor.serializer())
            subclass(UmlUseCase::class, UmlUseCase.serializer())
            subclass(UmlUseCaseSubject::class, UmlUseCaseSubject.serializer())
            subclass(UmlNode::class, UmlNode.serializer())
            subclass(UmlArtifact::class, UmlArtifact.serializer())
            subclass(UmlStereotype::class, UmlStereotype.serializer())
            subclass(UmlActivityNode::class, UmlActivityNode.serializer())
            subclass(UmlTimingLifeline::class, UmlTimingLifeline.serializer())
            subclass(UmlInteractionOverviewFrame::class, UmlInteractionOverviewFrame.serializer())
        }
    }
