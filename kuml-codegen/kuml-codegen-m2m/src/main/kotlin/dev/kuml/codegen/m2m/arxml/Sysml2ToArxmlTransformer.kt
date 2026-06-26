package dev.kuml.codegen.m2m.arxml

import dev.kuml.codegen.m2m.KumlTransformer
import dev.kuml.codegen.m2m.KumlTransformerProvider
import dev.kuml.codegen.m2m.TraceabilityLink
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.TransformTrace
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlPort
import java.util.UUID

/**
 * Transforms a [Sysml2Model] (with SysML 2 Block Definition structure) into a [KumlModel]
 * with AUTOSAR Classic stereotypes, ready for export via `ArxmlClassicExporter`.
 *
 * This is the reverse direction of [ArxmlToSysml2Transformer] and enables the
 * Classic → SysML 2 → Classic ARXML round-trip.
 *
 * **Mapping rules** (reverse of [ArxmlToSysml2Transformer]):
 * - [PartDefinition] WITHOUT `interfaceBlock=true` metadata → [UmlComponent] with stereotype `SoftwareComponent`
 *   and metadata `kind=application`. Port features typed by a [PartDefinition] with `portDefinition=true`
 *   are reverse-mapped to [UmlPort]s with stereotype `AutosarPort` and direction metadata.
 * - [PartDefinition] WITH `interfaceBlock=true` metadata → [UmlInterface] with stereotype `ComInterface`.
 * - Definitions with `portDefinition=true` metadata are consumed as port types and not independently
 *   mapped to UML elements.
 *
 * The resulting [KumlModel] wraps everything in a root [UmlPackage] named `AUTOSAR`,
 * with `language=UML` and `level=PIM` — the same shape expected by `ArxmlClassicExporter`.
 *
 * A [TransformTrace] is emitted with one [TraceabilityLink] per mapped element using rule ids:
 * - `block-to-swc` for PartDefinition → UmlComponent
 * - `portdef-to-port` for portDefinition PartDefinition feature → UmlPort
 * - `interfaceblock-to-interface` for interfaceBlock PartDefinition → UmlInterface
 *
 * V3.1.35 — initial implementation.
 */
public class Sysml2ToArxmlTransformer : KumlTransformer<Sysml2Model, KumlModel> {
    override val id: String = "sysml2-to-arxml"
    override val description: String =
        "SysML 2 BDD → AUTOSAR Classic UML model (autosarProfile stereotypes) ready for ArxmlClassicExporter"

    override fun transform(
        source: Sysml2Model,
        ctx: TransformContext,
    ): TransformResult<KumlModel> {
        var trace = TransformTrace()

        // Index all definitions for port type resolution
        val definitionsById = source.definitions.associateBy { it.id }

        val umlMembers = mutableListOf<dev.kuml.uml.UmlNamedElement>()

        for (def in source.definitions) {
            if (def !is PartDefinition) continue

            val isPortDef = (def.metadata["portDefinition"] as? KumlMetaValue.Text)?.value == "true"
            if (isPortDef) continue // consumed as port types, not top-level elements

            val isInterfaceBlock = (def.metadata["interfaceBlock"] as? KumlMetaValue.Text)?.value == "true"

            if (isInterfaceBlock) {
                val iface = mapInterfaceBlock(def)
                umlMembers.add(iface)
                trace =
                    trace.plus(
                        TraceabilityLink(
                            sourceElementId = def.id,
                            targetArtifactId = iface.id,
                            ruleId = RULE_INTERFACEBLOCK_TO_INTERFACE,
                        ),
                    )
            } else {
                val ports = mutableListOf<UmlPort>()
                for (feature in def.features) {
                    val portTypeId = feature.typeId ?: feature.definitionId ?: continue
                    val portTypeDef = definitionsById[portTypeId]
                    val direction =
                        (feature.metadata["direction"] as? KumlMetaValue.Text)?.value
                            ?: (portTypeDef?.metadata?.get("direction") as? KumlMetaValue.Text)?.value
                            ?: "provided"
                    val portId = UUID.randomUUID().toString()
                    val port =
                        UmlPort(
                            id = portId,
                            name = feature.name,
                            stereotypes = listOf("AutosarPort"),
                            metadata = mapOf("direction" to KumlMetaValue.Text(direction)),
                        )
                    ports.add(port)
                    trace =
                        trace.plus(
                            TraceabilityLink(
                                sourceElementId = feature.id,
                                targetArtifactId = portId,
                                ruleId = RULE_PORTDEF_TO_PORT,
                            ),
                        )
                }

                val compId = UUID.randomUUID().toString()
                val component =
                    UmlComponent(
                        id = compId,
                        name = def.name,
                        ports = ports,
                        stereotypes = listOf("SoftwareComponent"),
                        metadata = mapOf("kind" to KumlMetaValue.Text("application")),
                    )
                umlMembers.add(component)
                trace =
                    trace.plus(
                        TraceabilityLink(
                            sourceElementId = def.id,
                            targetArtifactId = compId,
                            ruleId = RULE_BLOCK_TO_SWC,
                        ),
                    )
            }
        }

        val rootPackage =
            UmlPackage(
                id = UUID.randomUUID().toString(),
                name = "AUTOSAR",
                members = umlMembers,
            )

        val kumlModel =
            KumlModel(
                root = rootPackage,
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = source.name,
            )

        return TransformResult.Success(kumlModel, trace)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun mapInterfaceBlock(def: PartDefinition): UmlInterface {
        val isService = (def.metadata["isService"] as? KumlMetaValue.Text)?.value == "true"
        return UmlInterface(
            id = UUID.randomUUID().toString(),
            name = def.name,
            stereotypes = listOf("ComInterface"),
            metadata =
                if (isService) {
                    mapOf("isService" to KumlMetaValue.Text("true"))
                } else {
                    emptyMap()
                },
        )
    }

    private companion object {
        const val RULE_BLOCK_TO_SWC = "block-to-swc"
        const val RULE_PORTDEF_TO_PORT = "portdef-to-port"
        const val RULE_INTERFACEBLOCK_TO_INTERFACE = "interfaceblock-to-interface"
    }
}

/** ServiceLoader provider for [Sysml2ToArxmlTransformer]. */
public class Sysml2ToArxmlTransformerProvider : KumlTransformerProvider {
    override fun transformer(): KumlTransformer<*, *> = Sysml2ToArxmlTransformer()
}
