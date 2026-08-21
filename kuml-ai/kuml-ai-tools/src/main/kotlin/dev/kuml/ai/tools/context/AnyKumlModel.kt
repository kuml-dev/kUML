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

/**
 * Rebuilds an [AnyKumlModel.Uml] editing-context seed from an already-extracted
 * [KumlDiagram] — the inverse of [AnyKumlModel.Uml.toKumlModel].
 *
 * KNOWN LOSS (flagged 2026-08-21, see the Branch-2 roundtrip test in
 * kuml-vault-examples-tests): [AnyKumlModel.Uml] has only two flat buckets —
 * [UmlNamedElement] and [UmlRelationship]. `dev.kuml.uml.UmlComment` is neither
 * (see its own KDoc: "intentionally NOT a UmlNamedElement") — free-standing or
 * anchored comments in [diagram] are silently dropped by this function. This is
 * a structural gap in [AnyKumlModel.Uml] itself, not a printer round-trip nuance
 * like `dev.kuml.uml.UmlPackage` (which the printer already flags with a TODO
 * comment). Fixing it would require a third bucket here plus matching changes in
 * DeepCopy/ModelMutationRouter/ScriptSerializer/PatchApplyEngine — out of scope
 * for this wave; deferred to a follow-up.
 *
 * Also out of scope: non-class UML diagrams (state machine, sequence/interaction)
 * — [AnyKumlModel.Uml] and `dev.kuml.ai.tools.uml.UmlEditingTools` only model
 * classifiers/relationships, so STM regions, interaction fragments, etc. are
 * dropped the same way if a non-class-diagram script is opened.
 */
public fun AnyKumlModel.Uml.Companion.fromKumlDiagram(diagram: KumlDiagram): AnyKumlModel.Uml =
    AnyKumlModel.Uml(
        name = diagram.name,
        diagramId = diagram.id,
        diagramType = diagram.type.name,
        elements = diagram.elements.filterIsInstance<UmlNamedElement>(),
        relationships = diagram.elements.filterIsInstance<UmlRelationship>(),
    )

/**
 * Rebuilds an [AnyKumlModel.Uml] editing-context seed from a rendered [KumlModel] —
 * the inverse of [AnyKumlModel.Uml.toKumlModel]. Delegates to [fromKumlDiagram] once
 * [model]'s root is confirmed to be a [KumlDiagram].
 *
 * See [fromKumlDiagram]'s KDoc for the known `UmlComment` / non-class-diagram loss
 * that applies equally here.
 */
public fun AnyKumlModel.Uml.Companion.fromKumlModel(model: KumlModel): AnyKumlModel.Uml {
    val diagram =
        model.root as? KumlDiagram
            ?: error("fromKumlModel: model.root is not a KumlDiagram (got ${model.root::class.simpleName})")
    return fromKumlDiagram(diagram)
}
