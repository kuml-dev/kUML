package dev.kuml.uml

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A UML association class — a modelling element with a genuine double nature per
 * UML 2.5 (§11.5.3): it is simultaneously a classifier (it owns attributes,
 * operations, and constraints, and can participate in generalization/realization
 * like [UmlClass]) and a relationship (it connects exactly two classifiers, like
 * [UmlAssociation]).
 *
 * ## Why one type, not two
 *
 * kUML deliberately models this as **one thing with one name and one [id]**,
 * implementing both [UmlClassifier] and [UmlRelationship] directly, rather than
 * pairing a [UmlClass] with a [UmlAssociation] plus a synthetic "link" object.
 * The two-object approach would give a single UML concept two identities in the
 * model — two IDs to keep in sync, two places a rename could drift apart, and an
 * invalid state (`class` without its `link`, or vice versa) that the type system
 * would happily allow. Modelling it as one type makes that invalid state
 * unrepresentable, which matters for an API published on Maven Central: once
 * `KumlDiagram.elements` ships with two objects per association class, removing
 * that redundancy is a breaking change.
 *
 * ## The one-ID, two-roles consequence
 *
 * Because [UmlAssociationClass] is both a node (classifier) and an edge
 * (relationship) under the *same* [id], the layout bridge registers that single
 * ID once as a [dev.kuml.layout.NodeId][kuml-layout-api NodeId] (so it gets a
 * class box) and once as an [dev.kuml.layout.EdgeId][kuml-layout-api EdgeId] (so
 * the association line between its two [ends] gets routed) — the same String
 * value keying two independent maps (`LayoutResult.nodes` / `LayoutResult.edges`),
 * never a lookup collision because the maps are distinct. See
 * `UmlLayoutBridge.toLayoutGraph` for the registration and
 * `KumlSvgRenderer` for how both roles get drawn from that one ID, including a
 * third, invisible anchor edge (ID suffix `#assocClassAnchor`) that exists purely
 * to give the layout engine a reason to place the class box near its
 * association line.
 *
 * ## V1 scope
 *
 * [ends] holds exactly two entries for any association class the DSL can build
 * or a renderer can draw a line for. UML 2.5 permits n-ary association classes
 * in principle; kUML's DSL does not expose a way to construct one, and consumers
 * that require exactly two ends (the DSL printers, the layout bridge, the SVG
 * renderer) treat `ends.size != 2` defensively — typically by falling back to a
 * classifier-only rendering with no association line, analogous to how
 * [UmlAssociation] with fewer than two ends is already handled.
 *
 * @property name Association class name. **Required**, unlike [UmlAssociation.name]
 *   (`String?`) — an association class is first and foremost a classifier, and
 *   classifiers are always named in kUML.
 * @property ends The two ends of the association side. No default — degenerate
 *   (0/1-end) instances are constructible for defensive-handling tests but are
 *   never produced by the DSL.
 * @property aggregation Aggregation kind for the association side (default: none),
 *   mirrors [UmlAssociation.aggregation].
 * @property isAbstract `true` for abstract association classes, mirrors [UmlClass.isAbstract].
 * @property attributes Owned properties (attributes), mirrors [UmlClass.attributes].
 * @property operations Owned operations, mirrors [UmlClass.operations].
 * @property constraints OCL or other constraints attached to this association class.
 */
@Serializable
data class UmlAssociationClass(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val ends: List<UmlAssociationEnd>,
    val aggregation: AggregationKind = AggregationKind.NONE,
    val isAbstract: Boolean = false,
    val attributes: List<UmlProperty> = emptyList(),
    val operations: List<UmlOperation> = emptyList(),
    val constraints: List<UmlConstraint> = emptyList(),
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
    override val appliedStereotypes: List<AppliedStereotype> = emptyList(),
) : UmlClassifier,
    UmlRelationship,
    Stereotypable
