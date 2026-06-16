package dev.kuml.io.emf

import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlModel
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.ParameterDirection
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.Visibility
import org.eclipse.uml2.uml.Classifier
import org.eclipse.uml2.uml.LiteralUnlimitedNatural
import org.eclipse.uml2.uml.NamedElement
import org.eclipse.uml2.uml.Operation
import org.eclipse.uml2.uml.ParameterDirectionKind
import org.eclipse.uml2.uml.Property
import org.eclipse.uml2.uml.Type
import org.eclipse.uml2.uml.UMLFactory
import org.eclipse.uml2.uml.VisibilityKind
import org.eclipse.uml2.uml.AggregationKind as EmfAggregationKind
import org.eclipse.uml2.uml.Class as EmfClass
import org.eclipse.uml2.uml.Interface as EmfInterface
import org.eclipse.uml2.uml.Model as EmfModel

/**
 * Konvertiert pure Kotlin kUML-Modelle → EMF/UML2-Modelle.
 *
 * V3.0.15 MVP: nur UmlClass → EMF Class (name, isAbstract, visibility).
 * V3.0.16 vervollständigt: alle Classifier-Typen, Properties, Operations,
 * Relationships (Association, Generalization, InterfaceRealization, Dependency).
 */
public class UmlToEmfConverter {
    private val factory = UMLFactory.eINSTANCE

    public fun convert(model: KumlModel): EmfModel {
        EmfBootstrap.init()
        val emfModel =
            factory.createModel().also { m ->
                m.name = model.name
            }
        val diagram = model.root
        if (diagram !is KumlDiagram) return emfModel

        // Index kUML-id → EMF element (needed for relationship resolution)
        val elementIndex = mutableMapOf<String, NamedElement>()

        // ── Classifier — must come first ────────────────────────────────────────
        diagram.elements.filterIsInstance<UmlClass>().forEach { cls ->
            elementIndex[cls.id] = convertClass(cls, emfModel)
        }
        diagram.elements.filterIsInstance<UmlInterface>().forEach { iface ->
            elementIndex[iface.id] = convertInterface(iface, emfModel)
        }
        diagram.elements.filterIsInstance<UmlEnumeration>().forEach { enum ->
            elementIndex[enum.id] = convertEnumeration(enum, emfModel)
        }

        // ── Relationships — after all classifiers are indexed ──────────────────
        diagram.elements.filterIsInstance<UmlAssociation>().forEach { assoc ->
            convertAssociation(assoc, emfModel, elementIndex)
        }
        diagram.elements.filterIsInstance<UmlGeneralization>().forEach { gen ->
            convertGeneralization(gen, elementIndex)
        }
        diagram.elements.filterIsInstance<UmlInterfaceRealization>().forEach { real ->
            convertInterfaceRealization(real, elementIndex)
        }
        diagram.elements.filterIsInstance<UmlDependency>().forEach { dep ->
            convertDependency(dep, emfModel, elementIndex)
        }

        return emfModel
    }

    // ── Classifier converters ──────────────────────────────────────────────────

    public fun convertClass(
        cls: UmlClass,
        emfModel: EmfModel,
    ): EmfClass {
        val emfClass = emfModel.createOwnedClass(cls.name, cls.isAbstract)
        emfClass.visibility = convertVisibility(cls.visibility)
        cls.attributes.forEach { prop -> convertProperty(prop, emfClass, emfModel) }
        cls.operations.forEach { op -> convertOperation(op, emfClass, emfModel) }
        return emfClass
    }

    public fun convertInterface(
        iface: UmlInterface,
        emfModel: EmfModel,
    ): EmfInterface {
        val emfInterface = emfModel.createOwnedInterface(iface.name)
        emfInterface.visibility = convertVisibility(iface.visibility)
        iface.attributes.forEach { prop -> convertInterfaceProperty(prop, emfInterface, emfModel) }
        iface.operations.forEach { op -> convertInterfaceOperation(op, emfInterface, emfModel) }
        return emfInterface
    }

    public fun convertEnumeration(
        enum: UmlEnumeration,
        emfModel: EmfModel,
    ): org.eclipse.uml2.uml.Enumeration {
        val emfEnum = emfModel.createOwnedEnumeration(enum.name)
        emfEnum.visibility = convertVisibility(enum.visibility)
        enum.literals.forEach { lit ->
            val emfLit = emfEnum.createOwnedLiteral(lit.name)
            emfLit.visibility = convertVisibility(lit.visibility)
        }
        return emfEnum
    }

    // ── Feature converters ─────────────────────────────────────────────────────

    private fun convertProperty(
        prop: UmlProperty,
        owner: EmfClass,
        emfModel: EmfModel,
    ): Property {
        val type = resolveOrCreatePrimitiveType(prop.type.name, emfModel)
        val emfProp = owner.createOwnedAttribute(prop.name, type)
        applyPropertyDetails(emfProp, prop)
        return emfProp
    }

    private fun convertInterfaceProperty(
        prop: UmlProperty,
        owner: EmfInterface,
        emfModel: EmfModel,
    ): Property {
        val type = resolveOrCreatePrimitiveType(prop.type.name, emfModel)
        val emfProp = owner.createOwnedAttribute(prop.name, type)
        applyPropertyDetails(emfProp, prop)
        return emfProp
    }

    private fun applyPropertyDetails(
        emfProp: Property,
        prop: UmlProperty,
    ) {
        emfProp.visibility = convertVisibility(prop.visibility)
        applyMultiplicity(emfProp, prop.multiplicity)
        emfProp.setIsStatic(prop.isStatic)
        emfProp.setIsReadOnly(prop.isReadOnly)
        if (prop.defaultValue != null) {
            emfProp.default = prop.defaultValue
        }
    }

    private fun convertOperation(
        op: UmlOperation,
        owner: EmfClass,
        emfModel: EmfModel,
    ): Operation {
        val emfOp = owner.createOwnedOperation(op.name, null, null)
        applyOperationDetails(emfOp, op, emfModel)
        return emfOp
    }

    private fun convertInterfaceOperation(
        op: UmlOperation,
        owner: EmfInterface,
        emfModel: EmfModel,
    ): Operation {
        val emfOp = owner.createOwnedOperation(op.name, null, null)
        applyOperationDetails(emfOp, op, emfModel)
        return emfOp
    }

    private fun applyOperationDetails(
        emfOp: Operation,
        op: UmlOperation,
        emfModel: EmfModel,
    ) {
        emfOp.visibility = convertVisibility(op.visibility)
        emfOp.setIsAbstract(op.isAbstract)
        emfOp.setIsStatic(op.isStatic)

        // Return type via createReturnResult
        val returnTypeRef = op.returnType
        if (returnTypeRef != null) {
            val retType = resolveOrCreatePrimitiveType(returnTypeRef.name, emfModel)
            emfOp.createReturnResult(null, retType)
        }

        // IN/OUT/INOUT parameters
        op.parameters.forEach { param ->
            val paramType = resolveOrCreatePrimitiveType(param.type.name, emfModel)
            val emfParam = emfOp.createOwnedParameter(param.name, paramType)
            emfParam.direction = convertDirection(param.direction)
            if (param.defaultValue != null) {
                emfParam.default = param.defaultValue
            }
        }
    }

    // ── Relationship converters ────────────────────────────────────────────────

    private fun convertAssociation(
        assoc: UmlAssociation,
        emfModel: EmfModel,
        index: Map<String, NamedElement>,
    ) {
        val ends = assoc.ends
        if (ends.size < 2) return
        val end0 = ends[0]
        val end1 = ends[1]

        val type0 = index[end0.typeId] as? Type ?: return
        val type1 = index[end1.typeId] as? Type ?: return

        val emfAggregation = convertAggregation(assoc.aggregation)

        // Use createOwnedAssociation on the package (Model extends Package)
        val emfAssoc =
            emfModel.createOwnedType(
                assoc.id,
                org.eclipse.uml2.uml.UMLPackage.eINSTANCE.association,
            ) as org.eclipse.uml2.uml.Association

        if (assoc.name != null) {
            emfAssoc.name = assoc.name
        }

        // Create ends manually to have full control over aggregation + navigability
        val emfEnd0 =
            if (end0.navigable) {
                emfAssoc.createNavigableOwnedEnd(end0.role ?: "", type0)
            } else {
                emfAssoc.createOwnedEnd(end0.role ?: "", type0)
            }
        emfEnd0.aggregation = if (assoc.aggregation != AggregationKind.NONE) emfAggregation else EmfAggregationKind.NONE_LITERAL
        applyMultiplicity(emfEnd0, end0.multiplicity)

        val emfEnd1 =
            if (end1.navigable) {
                emfAssoc.createNavigableOwnedEnd(end1.role ?: "", type1)
            } else {
                emfAssoc.createOwnedEnd(end1.role ?: "", type1)
            }
        emfEnd1.aggregation = EmfAggregationKind.NONE_LITERAL
        applyMultiplicity(emfEnd1, end1.multiplicity)
    }

    private fun convertGeneralization(
        gen: UmlGeneralization,
        index: Map<String, NamedElement>,
    ) {
        val specific = index[gen.specificId] as? Classifier ?: return
        val general = index[gen.generalId] as? Classifier ?: return
        specific.createGeneralization(general)
    }

    private fun convertInterfaceRealization(
        real: UmlInterfaceRealization,
        index: Map<String, NamedElement>,
    ) {
        val implementing = index[real.implementingId] as? org.eclipse.uml2.uml.BehavioredClassifier ?: return
        val iface = index[real.interfaceId] as? EmfInterface ?: return
        implementing.createInterfaceRealization(null, iface)
    }

    private fun convertDependency(
        dep: UmlDependency,
        emfModel: EmfModel,
        index: Map<String, NamedElement>,
    ) {
        val client = index[dep.clientId] ?: return
        val supplier = index[dep.supplierId] ?: return
        val emfDep = factory.createDependency()
        emfDep.name = dep.name ?: ""
        emfDep.clients.add(client)
        emfDep.suppliers.add(supplier)
        emfModel.packagedElements.add(emfDep)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun resolveOrCreatePrimitiveType(
        typeName: String,
        emfModel: EmfModel,
    ): org.eclipse.uml2.uml.PrimitiveType {
        // Reuse an existing PrimitiveType with the same name if already created
        val existing =
            emfModel.ownedTypes
                .filterIsInstance<org.eclipse.uml2.uml.PrimitiveType>()
                .firstOrNull { it.name == typeName }
        return existing ?: emfModel.createOwnedPrimitiveType(typeName)
    }

    private fun applyMultiplicity(
        elem: org.eclipse.uml2.uml.MultiplicityElement,
        mult: Multiplicity,
    ) {
        elem.setLower(mult.lower)
        elem.setUpper(mult.upper ?: LiteralUnlimitedNatural.UNLIMITED)
    }

    private fun convertVisibility(vis: Visibility): VisibilityKind =
        when (vis) {
            Visibility.PRIVATE -> VisibilityKind.PRIVATE_LITERAL
            Visibility.PROTECTED -> VisibilityKind.PROTECTED_LITERAL
            Visibility.PACKAGE -> VisibilityKind.PACKAGE_LITERAL
            Visibility.PUBLIC -> VisibilityKind.PUBLIC_LITERAL
        }

    private fun convertDirection(dir: ParameterDirection): ParameterDirectionKind =
        when (dir) {
            ParameterDirection.OUT -> ParameterDirectionKind.OUT_LITERAL
            ParameterDirection.INOUT -> ParameterDirectionKind.INOUT_LITERAL
            ParameterDirection.RETURN -> ParameterDirectionKind.RETURN_LITERAL
            else -> ParameterDirectionKind.IN_LITERAL
        }

    private fun convertAggregation(kind: AggregationKind): EmfAggregationKind =
        when (kind) {
            AggregationKind.SHARED -> EmfAggregationKind.SHARED_LITERAL
            AggregationKind.COMPOSITE -> EmfAggregationKind.COMPOSITE_LITERAL
            else -> EmfAggregationKind.NONE_LITERAL
        }
}
