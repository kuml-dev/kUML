package dev.kuml.io.emf

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.ParameterDirection
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlParameter
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility
import org.eclipse.uml2.uml.Association
import org.eclipse.uml2.uml.Dependency
import org.eclipse.uml2.uml.Enumeration
import org.eclipse.uml2.uml.LiteralUnlimitedNatural
import org.eclipse.uml2.uml.Operation
import org.eclipse.uml2.uml.Parameter
import org.eclipse.uml2.uml.ParameterDirectionKind
import org.eclipse.uml2.uml.Property
import org.eclipse.uml2.uml.Type
import org.eclipse.uml2.uml.VisibilityKind
import org.eclipse.uml2.uml.AggregationKind as EmfAggregationKind
import org.eclipse.uml2.uml.Class as EmfClass
import org.eclipse.uml2.uml.Interface as EmfInterface
import org.eclipse.uml2.uml.Model as EmfModel

/**
 * Konvertiert EMF/UML2-Modelle → pure Kotlin kUML-Modelle.
 *
 * V3.0.15 MVP: nur UmlClass-Konvertierung (name, isAbstract, visibility).
 * V3.0.16 vervollständigt: alle Classifier-Typen, Properties, Operations,
 * Relationships (Association, Generalization, InterfaceRealization, Dependency).
 */
public class EmfToUmlConverter {
    public fun convert(emfModel: EmfModel): KumlModel {
        EmfBootstrap.init()
        val allElements = emfModel.allOwnedElements().toList()
        val elements = mutableListOf<KumlElement>()

        // ── Classifier ──────────────────────────────────────────────────────────
        allElements.filterIsInstance<EmfClass>().mapTo(elements) { convertClass(it) }
        allElements.filterIsInstance<EmfInterface>().mapTo(elements) { convertInterface(it) }
        allElements.filterIsInstance<Enumeration>().mapTo(elements) { convertEnumeration(it) }

        // ── Relationships ───────────────────────────────────────────────────────
        // Association: owned by the package/model namespace
        allElements.filterIsInstance<Association>().mapTo(elements) { convertAssociation(it) }

        // Generalization & InterfaceRealization: owned by the specific class,
        // not by the model namespace — traverse all Classes to extract them.
        allElements.filterIsInstance<EmfClass>().forEach { emfClass ->
            emfClass.generalizations.forEach { gen ->
                elements.add(
                    UmlGeneralization(
                        id = "${emfClass.name ?: "?"}-gen-${gen.general?.name ?: "?"}",
                        specificId = emfClass.name ?: "",
                        generalId = gen.general?.name ?: "",
                    ),
                )
            }
            emfClass.interfaceRealizations.forEach { real ->
                elements.add(
                    UmlInterfaceRealization(
                        id = "${emfClass.name ?: "?"}-real-${real.contract?.name ?: "?"}",
                        implementingId = emfClass.name ?: "",
                        interfaceId = real.contract?.name ?: "",
                    ),
                )
            }
        }

        // Dependency: owned by the model namespace
        allElements
            .filterIsInstance<Dependency>()
            // Filter out InterfaceRealization which is a subtype of Realization which is a subtype of Dependency
            .filter { it !is org.eclipse.uml2.uml.InterfaceRealization }
            .mapTo(elements) { convertDependency(it) }

        val diagram =
            KumlDiagram(
                id = emfModel.name ?: "emf-import",
                name = emfModel.name ?: "EMF Import",
                type = DiagramType.CLASS,
                elements = elements,
            )
        return KumlModel(
            root = diagram,
            language = ModelingLanguage.UML,
            level = ModelLevel.PIM,
            name = emfModel.name ?: "EMF Import",
        )
    }

    // ── Classifier converters ──────────────────────────────────────────────────

    public fun convertClass(emfClass: EmfClass): UmlClass =
        UmlClass(
            id = emfClass.name ?: "unnamed",
            name = emfClass.name ?: "Unnamed",
            isAbstract = emfClass.isAbstract,
            visibility = convertVisibility(emfClass.visibility),
            attributes = emfClass.ownedAttributes.map { convertProperty(it) },
            operations = emfClass.ownedOperations.map { convertOperation(it) },
        )

    public fun convertInterface(emfInterface: EmfInterface): UmlInterface =
        UmlInterface(
            id = emfInterface.name ?: "unnamed",
            name = emfInterface.name ?: "Unnamed",
            visibility = convertVisibility(emfInterface.visibility),
            attributes = emfInterface.ownedAttributes.map { convertProperty(it) },
            operations = emfInterface.ownedOperations.map { convertOperation(it) },
        )

    public fun convertEnumeration(emfEnum: Enumeration): UmlEnumeration =
        UmlEnumeration(
            id = emfEnum.name ?: "unnamed",
            name = emfEnum.name ?: "Unnamed",
            visibility = convertVisibility(emfEnum.visibility),
            literals =
                emfEnum.ownedLiterals.map { lit ->
                    UmlEnumerationLiteral(
                        id = "${emfEnum.name ?: "?"}_${lit.name ?: "?"}",
                        name = lit.name ?: "Unnamed",
                        visibility = convertVisibility(lit.visibility),
                    )
                },
        )

    // ── Feature converters ─────────────────────────────────────────────────────

    public fun convertProperty(emfProp: Property): UmlProperty =
        UmlProperty(
            id = "${emfProp.owner?.let { (it as? org.eclipse.uml2.uml.NamedElement)?.name ?: "?" } ?: "?"}.${emfProp.name ?: "?"}",
            name = emfProp.name ?: "unnamed",
            visibility = convertVisibility(emfProp.visibility),
            type = convertTypeRef(emfProp.type),
            multiplicity = convertMultiplicity(emfProp),
            defaultValue = emfProp.default?.takeIf { it.isNotBlank() },
            isStatic = emfProp.isStatic,
            isReadOnly = emfProp.isReadOnly,
        )

    public fun convertOperation(emfOp: Operation): UmlOperation {
        val returnParam = emfOp.getReturnResult()
        val inParams =
            emfOp.ownedParameters
                .filter { it.direction != ParameterDirectionKind.RETURN_LITERAL }
                .map { convertParameter(it) }
        val returnType = returnParam?.let { convertTypeRef(it.type) }
        return UmlOperation(
            id = "${emfOp.owner?.let { (it as? org.eclipse.uml2.uml.NamedElement)?.name ?: "?" } ?: "?"}.${emfOp.name ?: "?"}",
            name = emfOp.name ?: "unnamed",
            visibility = convertVisibility(emfOp.visibility),
            parameters = inParams,
            returnType = returnType,
            isAbstract = emfOp.isAbstract,
            isStatic = emfOp.isStatic,
        )
    }

    private fun convertParameter(emfParam: Parameter): UmlParameter =
        UmlParameter(
            id = "${emfParam.operation?.name ?: "?"}.${emfParam.name ?: "?"}",
            name = emfParam.name ?: "unnamed",
            type = convertTypeRef(emfParam.type),
            direction = convertDirection(emfParam.direction),
            defaultValue = emfParam.default?.takeIf { it.isNotBlank() },
        )

    // ── Relationship converters ────────────────────────────────────────────────

    public fun convertAssociation(emfAssoc: Association): UmlAssociation {
        val memberEnds = emfAssoc.memberEnds
        val end0 = memberEnds.getOrNull(0)
        val end1 = memberEnds.getOrNull(1)
        val ends =
            listOfNotNull(end0, end1).map { prop ->
                UmlAssociationEnd(
                    typeId = prop.type?.name ?: "",
                    role = prop.name?.takeIf { it.isNotBlank() },
                    multiplicity = convertMultiplicity(prop),
                    navigable = emfAssoc.navigableOwnedEnds.contains(prop) || prop.owner !is Association,
                )
            }
        // Determine aggregation from the ends: first COMPOSITE or SHARED end wins
        val aggregation =
            listOfNotNull(end0, end1)
                .map { convertAggregation(it.aggregation) }
                .firstOrNull { it != AggregationKind.NONE } ?: AggregationKind.NONE
        return UmlAssociation(
            id = emfAssoc.name?.takeIf { it.isNotBlank() } ?: "assoc-${ends.joinToString("-") { it.typeId }}",
            name = emfAssoc.name?.takeIf { it.isNotBlank() },
            ends = ends,
            aggregation = aggregation,
        )
    }

    public fun convertDependency(emfDep: Dependency): UmlDependency {
        val client = emfDep.clients.firstOrNull()?.name ?: ""
        val supplier = emfDep.suppliers.firstOrNull()?.name ?: ""
        return UmlDependency(
            id = emfDep.name?.takeIf { it.isNotBlank() } ?: "dep-$client-$supplier",
            clientId = client,
            supplierId = supplier,
            name = emfDep.name?.takeIf { it.isNotBlank() },
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun convertVisibility(vis: VisibilityKind): Visibility =
        when (vis) {
            VisibilityKind.PRIVATE_LITERAL -> Visibility.PRIVATE
            VisibilityKind.PROTECTED_LITERAL -> Visibility.PROTECTED
            VisibilityKind.PACKAGE_LITERAL -> Visibility.PACKAGE
            else -> Visibility.PUBLIC
        }

    private fun convertTypeRef(type: Type?): UmlTypeRef = UmlTypeRef(name = type?.name ?: "")

    private fun convertMultiplicity(elem: org.eclipse.uml2.uml.MultiplicityElement): Multiplicity {
        val lower = elem.lower
        val upperRaw = elem.upper
        val upper = if (upperRaw == LiteralUnlimitedNatural.UNLIMITED) null else upperRaw
        return Multiplicity(lower = lower, upper = upper)
    }

    private fun convertDirection(dir: ParameterDirectionKind): ParameterDirection =
        when (dir) {
            ParameterDirectionKind.OUT_LITERAL -> ParameterDirection.OUT
            ParameterDirectionKind.INOUT_LITERAL -> ParameterDirection.INOUT
            ParameterDirectionKind.RETURN_LITERAL -> ParameterDirection.RETURN
            else -> ParameterDirection.IN
        }

    private fun convertAggregation(kind: EmfAggregationKind): AggregationKind =
        when (kind) {
            EmfAggregationKind.SHARED_LITERAL -> AggregationKind.SHARED
            EmfAggregationKind.COMPOSITE_LITERAL -> AggregationKind.COMPOSITE
            else -> AggregationKind.NONE
        }
}
