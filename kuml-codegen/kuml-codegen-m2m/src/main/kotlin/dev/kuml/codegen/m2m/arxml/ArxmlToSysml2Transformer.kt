package dev.kuml.codegen.m2m.arxml

import dev.kuml.codegen.m2m.KumlTransformer
import dev.kuml.codegen.m2m.KumlTransformerProvider
import dev.kuml.codegen.m2m.TraceabilityLink
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.TransformTrace
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.KumlModel
import dev.kuml.kerml.KermlFeature
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PortDefinition
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlPort
import java.util.UUID

/**
 * Transforms a [KumlModel] (with AUTOSAR Classic stereotypes) into a SysML 2 Block Definition structure.
 *
 * **Mapping rules**:
 * - [UmlComponent] with stereotype `SoftwareComponent` → [PartDefinition] (Block); metadata `kind=application`
 * - [UmlPort] with stereotype `AutosarPort` → a [PortDefinition] (one per unique port name/direction)
 *   plus a [KermlFeature] typed by the port-definition id on the owning PartDefinition;
 *   direction metadata (`provided`/`required`) is preserved.
 * - [UmlInterface] with stereotype `ComInterface` → a [PartDefinition] tagged
 *   `metadata interfaceBlock=true` and `stereotype=interface`.
 *   Note: SysML 2 has no dedicated InterfaceBlock metaclass; the convention here is to model
 *   an InterfaceBlock as a [PartDefinition] with `interfaceBlock=true` metadata.
 *
 * A [BdDiagram] listing all produced definitions is included in the resulting [Sysml2Model].
 *
 * A [TransformTrace] is emitted with one [TraceabilityLink] per mapped element using rule ids:
 * - `swc-to-block` for SoftwareComponent → PartDefinition
 * - `port-to-portdef` for AutosarPort → PortDefinition + KermlFeature
 * - `interface-to-interfaceblock` for ComInterface → interfaceBlock PartDefinition
 *
 * V3.1.35 — initial implementation.
 */
public class ArxmlToSysml2Transformer : KumlTransformer<KumlModel, Sysml2Model> {
    override val id: String = "arxml-to-sysml2"
    override val description: String =
        "AUTOSAR Classic UML model (autosarProfile stereotypes) → SysML 2 BDD (Blocks + InterfaceBlocks)"

    override fun transform(
        source: KumlModel,
        ctx: TransformContext,
    ): TransformResult<Sysml2Model> {
        val definitions = mutableListOf<PartDefinition>()
        val portDefinitions = mutableListOf<PortDefinition>()
        var trace = TransformTrace()

        // Collect all UML elements from the model tree
        val rootPackage =
            source.root as? UmlPackage ?: return TransformResult.Failure(
                listOf(
                    dev.kuml.codegen.m2m
                        .TransformError("Model root must be a UmlPackage"),
                ),
            )

        collectFromPackage(rootPackage, definitions, portDefinitions) { link ->
            trace = trace.plus(link)
        }

        val allIds = (definitions.map { it.id } + portDefinitions.map { it.id }).distinct()
        val diagram = BdDiagram(name = "${source.name} — SysML 2 BDD", elementIds = allIds)

        // PortDefinitions are not Sysml2Definitions but we include them so the bridge
        // can render them. We store them as standalone PartDefinitions in the definitions list
        // since PortDefinition is already a Sysml2Definition.
        val portDefsAsDefinitions: List<PartDefinition> =
            portDefinitions.map { pd ->
                PartDefinition(
                    id = pd.id,
                    name = pd.name,
                    metadata = pd.metadata + mapOf("portDefinition" to KumlMetaValue.Text("true")),
                )
            }

        val sysml2Model =
            Sysml2Model(
                name = "${source.name} — SysML 2",
                definitions = definitions + portDefsAsDefinitions,
                diagrams = listOf(diagram),
            )

        return TransformResult.Success(sysml2Model, trace)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun collectFromPackage(
        pkg: UmlPackage,
        definitions: MutableList<PartDefinition>,
        portDefinitions: MutableList<PortDefinition>,
        addLink: (TraceabilityLink) -> Unit,
    ) {
        for (member in pkg.members) {
            when (member) {
                is UmlPackage -> collectFromPackage(member, definitions, portDefinitions, addLink)
                is UmlComponent -> mapComponent(member, definitions, portDefinitions, addLink)
                is UmlInterface -> mapInterface(member, definitions, addLink)
                else -> { /* skip other element types */ }
            }
        }
    }

    private fun mapComponent(
        component: UmlComponent,
        definitions: MutableList<PartDefinition>,
        portDefinitions: MutableList<PortDefinition>,
        addLink: (TraceabilityLink) -> Unit,
    ) {
        val partId = UUID.randomUUID().toString()

        // Build PortDefinitions and KermlFeatures for each port
        val portFeatures = mutableListOf<KermlFeature>()
        for (port in component.ports) {
            val (portDef, feature) = mapPort(port)
            portDefinitions.add(portDef)
            portFeatures.add(feature)
            addLink(
                TraceabilityLink(
                    sourceElementId = port.id,
                    targetArtifactId = portDef.id,
                    ruleId = RULE_PORT_TO_PORTDEF,
                ),
            )
        }

        val partDef =
            PartDefinition(
                id = partId,
                name = component.name,
                features = portFeatures,
                metadata =
                    mapOf(
                        "kind" to KumlMetaValue.Text("application"),
                        "stereotype" to KumlMetaValue.Text("SoftwareComponent"),
                    ),
            )

        definitions.add(partDef)
        addLink(
            TraceabilityLink(
                sourceElementId = component.id,
                targetArtifactId = partId,
                ruleId = RULE_SWC_TO_BLOCK,
            ),
        )
    }

    private fun mapPort(port: UmlPort): Pair<PortDefinition, KermlFeature> {
        val portDefId = UUID.randomUUID().toString()
        val direction = (port.metadata["direction"] as? KumlMetaValue.Text)?.value ?: "provided"

        val portDef =
            PortDefinition(
                id = portDefId,
                name = "${port.name}Type",
                metadata =
                    mapOf(
                        "direction" to KumlMetaValue.Text(direction),
                        "stereotype" to KumlMetaValue.Text("AutosarPort"),
                    ),
            )

        val feature =
            KermlFeature(
                id = UUID.randomUUID().toString(),
                name = port.name,
                typeId = portDefId,
                definitionId = portDefId,
                metadata = mapOf("direction" to KumlMetaValue.Text(direction)),
            )

        return Pair(portDef, feature)
    }

    private fun mapInterface(
        iface: UmlInterface,
        definitions: MutableList<PartDefinition>,
        addLink: (TraceabilityLink) -> Unit,
    ) {
        val partId = UUID.randomUUID().toString()
        val isService = (iface.metadata["isService"] as? KumlMetaValue.Text)?.value == "true"

        val partDef =
            PartDefinition(
                id = partId,
                name = iface.name,
                metadata =
                    buildMap {
                        put("interfaceBlock", KumlMetaValue.Text("true"))
                        put("stereotype", KumlMetaValue.Text("interface"))
                        if (isService) put("isService", KumlMetaValue.Text("true"))
                    },
            )

        definitions.add(partDef)
        addLink(
            TraceabilityLink(
                sourceElementId = iface.id,
                targetArtifactId = partId,
                ruleId = RULE_INTERFACE_TO_INTERFACEBLOCK,
            ),
        )
    }

    private companion object {
        const val RULE_SWC_TO_BLOCK = "swc-to-block"
        const val RULE_PORT_TO_PORTDEF = "port-to-portdef"
        const val RULE_INTERFACE_TO_INTERFACEBLOCK = "interface-to-interfaceblock"
    }
}

/** ServiceLoader provider for [ArxmlToSysml2Transformer]. */
public class ArxmlToSysml2TransformerProvider : KumlTransformerProvider {
    override fun transformer(): KumlTransformer<*, *> = ArxmlToSysml2Transformer()
}
