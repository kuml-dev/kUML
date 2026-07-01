package dev.kuml.uml

import dev.kuml.core.model.KumlElement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Guards [UmlSerializersModule] against silently missing a concrete UML
 * subtype registration.
 *
 * Every concrete [KumlElement] implementation used elsewhere in this module
 * (see `KumlDiagramSerializationTest`, `SerializationTest`) must be
 * round-trippable through the [KumlElement] polymorphic base via
 * [UmlSerializersModule]. If a new UML type is added to the metamodel but
 * forgotten in `UmlSerializersModule.kt`, the corresponding case below fails
 * loudly instead of silently throwing only at first real-world use.
 */
class UmlSerializersModuleTest :
    FunSpec(body = {

        val json =
            Json {
                serializersModule = UmlSerializersModule
                classDiscriminator = "@type"
            }

        val sampleElements: List<KumlElement> =
            listOf(
                UmlClass(id = "c", name = "c"),
                UmlInterface(id = "i", name = "i"),
                UmlEnumeration(id = "e", name = "e"),
                UmlEnumerationLiteral(id = "el", name = "el"),
                UmlPackage(id = "p", name = "p"),
                UmlCollaboration(id = "col", name = "col"),
                UmlCollaborationRole(id = "cr", name = "cr", type = UmlTypeRef(name = "c")),
                UmlComponent(id = "comp", name = "comp"),
                UmlPort(id = "port", name = "port"),
                UmlConnector(id = "conn", end1Id = "a", end2Id = "b"),
                UmlProperty(id = "prop", name = "prop", type = UmlTypeRef(name = "String")),
                UmlOperation(id = "op", name = "op"),
                UmlParameter(id = "param", name = "param", type = UmlTypeRef(name = "String")),
                UmlConstraint(id = "constr", name = "constr", body = "true"),
                UmlInteraction(id = "int", name = "int"),
                UmlLifeline(id = "ll", name = "ll"),
                UmlMessage(id = "msg", label = "m", fromLifelineId = "a", toLifelineId = "b", sequence = 1),
                UmlCombinedFragment(id = "cf", operator = InteractionOperator.ALT, operands = emptyList()),
                UmlInstanceSpecification(id = "instSpec", name = "instSpec", classifierId = "c", classifierName = "c"),
                UmlLink(id = "link", associationId = "a", sourceInstanceId = "s", targetInstanceId = "t"),
                UmlStateMachine(id = "sm", name = "sm"),
                UmlState(id = "st", name = "st"),
                UmlPseudostate(id = "ps", name = "ps", kind = PseudostateKind.INITIAL),
                UmlFinalState(id = "fs", name = "fs"),
                UmlTransition(id = "tr", sourceId = "a", targetId = "b"),
                UmlAssociation(id = "assoc", ends = listOf(UmlAssociationEnd(typeId = "a"), UmlAssociationEnd(typeId = "b"))),
                UmlGeneralization(id = "gen", specificId = "a", generalId = "b"),
                UmlInterfaceRealization(id = "ir", implementingId = "a", interfaceId = "b"),
                UmlDependency(id = "dep", clientId = "a", supplierId = "b"),
                UmlActor(id = "actor", name = "actor"),
                UmlUseCase(id = "uc", name = "uc"),
                UmlUseCaseSubject(id = "ucs", name = "ucs"),
                UmlInclude(id = "inc", baseId = "a", additionId = "b"),
                UmlExtend(id = "ext", baseId = "a", extensionId = "b"),
                UmlNode(id = "node", name = "node"),
                UmlArtifact(id = "art", name = "art"),
                UmlStereotype(id = "st2", name = "st2"),
                UmlActivityNode(id = "an", name = "an", kind = UmlActivityNodeKind.ACTION),
                UmlActivityEdge(id = "ae", sourceId = "a", targetId = "b"),
                UmlTimingLifeline(id = "tl", name = "tl"),
                UmlInteractionOverviewFrame(id = "iof", name = "iof", kind = UmlInteractionFrameKind.INTERACTION_REF),
            )

        test(name = "every concrete UML KumlElement is registered in UmlSerializersModule") {
            sampleElements.forEach { element ->
                val text = json.encodeToString(element)
                val decoded = json.decodeFromString<KumlElement>(text)
                decoded shouldBe element
            }
        }
    })
