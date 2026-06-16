package dev.kuml.ai.tools.context

import dev.kuml.c4.model.C4Model
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlRelationship
import kotlinx.serialization.Serializable

/**
 * Sealed envelope across the three top-level model roots that the agent may edit.
 *
 * Not part of the kUML metamodel; this is a UI/AI-layer construct that lets one
 * AgentEditingContext drive any of UML / C4 / SysML 2 from a single Koog ToolSet
 * surface. Each subtype delegates structural mutations to its own *PatchOps file.
 *
 * Design note: KumlModel / KumlDiagram are not @Serializable, so the UML variant
 * stores elements + relationships as flat lists and builds a KumlModel on demand
 * (toKumlModel()). C4Model and Sysml2Model ARE @Serializable and are held directly.
 */
@Serializable
public sealed interface AnyKumlModel {
    /**
     * UML editing context. Elements and relationships are stored flat so they are
     * individually serializable for JSON-roundtrip deep-copy.
     */
    @Serializable
    public data class Uml(
        val name: String,
        val diagramId: String = "agent-default-class-diagram",
        val diagramType: String = "CLASS",
        val elements: List<UmlNamedElement> = emptyList(),
        val relationships: List<UmlRelationship> = emptyList(),
    ) : AnyKumlModel {
        /** Build the runtime KumlModel for rendering / simulation. */
        public fun toKumlModel(): KumlModel {
            val allKumlElements: List<KumlElement> =
                elements.map { it as KumlElement } + relationships.map { it as KumlElement }
            val diagram =
                KumlDiagram(
                    id = diagramId,
                    name = name,
                    type = DiagramType.valueOf(diagramType),
                    elements = allKumlElements,
                )
            return KumlModel(
                root = diagram,
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = name,
            )
        }
    }

    @Serializable
    public data class C4(
        val model: C4Model,
    ) : AnyKumlModel

    @Serializable
    public data class Sysml2(
        val model: Sysml2Model,
    ) : AnyKumlModel

    public companion object {
        /** Creates a minimal empty UML model suitable as an editing seed. */
        public fun emptyUml(name: String = "AgentModel"): Uml = Uml(name = name)

        /** Creates a minimal empty C4 model. */
        public fun emptyC4(name: String = "AgentC4"): C4 = C4(model = C4Model(id = "agent-default-c4", name = name))

        /** Creates a minimal empty SysML 2 model. */
        public fun emptySysml2(name: String = "AgentSysml2"): Sysml2 = Sysml2(model = Sysml2Model(name = name))
    }
}
