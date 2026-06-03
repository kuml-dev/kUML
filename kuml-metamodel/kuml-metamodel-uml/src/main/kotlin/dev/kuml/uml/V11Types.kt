package dev.kuml.uml

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// V1.1 — new metamodel types added to support the remaining UML 2.x diagram
// types. Kept in a single file so the addition is easy to spot in git blame.
//
// Each section below holds the minimum types needed to make its diagram
// renderable in the V1.1 first-cut. More exotic UML 2.x specialisations
// (e.g. structured activities, ports on nodes, full collaboration roles)
// remain deferred to V2.
// ─────────────────────────────────────────────────────────────────────────────

// ── Deployment ───────────────────────────────────────────────────────────────

/**
 * A UML 2.x deployment node — a runtime execution environment that hosts
 * artifacts. Renders as a 3D cube. Nested [children] are supported (e.g. a
 * "Cluster" node containing several "Pod" nodes).
 *
 * @property nodeKind `"node"` for hardware, `"executionEnvironment"` for a
 *  software container (renders with the `«executionEnvironment»` stereotype).
 */
@Serializable
data class UmlNode(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val nodeKind: String = "node",
    val children: List<UmlNamedElement> = emptyList(),
    val artifacts: List<UmlArtifact> = emptyList(),
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlNamedElement

/**
 * A UML 2.x artifact — a physical piece of information that can be deployed
 * onto a [UmlNode] (e.g. a JAR, a Docker image, a config file).
 */
@Serializable
data class UmlArtifact(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val fileName: String? = null,
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlNamedElement

// ── Profile ──────────────────────────────────────────────────────────────────

/**
 * A UML 2.x stereotype — a profile extension that can be applied to instances
 * of a metaclass via the `«stereotype»` label.
 */
@Serializable
data class UmlStereotype(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    /** Metaclass names this stereotype may extend (e.g. `Class`, `Property`). */
    val metaclasses: List<String> = emptyList(),
    /** Tagged-value properties defined on this stereotype. */
    val tagDefinitions: List<UmlProperty> = emptyList(),
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlNamedElement

// ── Activity ─────────────────────────────────────────────────────────────────

/** Kind discriminator for [UmlActivityNode] variants. */
@Serializable
public enum class UmlActivityNodeKind {
    /** A unit of executable work (rounded rectangle). */
    ACTION,

    /** Initial pseudo-node (filled circle). */
    INITIAL,

    /** Final activity node (filled circle inside a ring). */
    ACTIVITY_FINAL,

    /** Flow final (circle with X). */
    FLOW_FINAL,

    /** Decision node (diamond). */
    DECISION,

    /** Merge node (diamond, n inputs / 1 output). */
    MERGE,

    /** Fork node (vertical bar). */
    FORK,

    /** Join node (vertical bar, n inputs / 1 output). */
    JOIN,

    /** Object node (rectangle, datum flowing). */
    OBJECT,
}

/**
 * A UML 2.x activity-diagram node. The single [kind] discriminator keeps the
 * renderer dispatch on a flat sealed-class equivalent without proliferating
 * specific data classes for each variant.
 */
@Serializable
data class UmlActivityNode(
    override val id: String,
    override val name: String,
    val kind: UmlActivityNodeKind,
    override val visibility: Visibility = Visibility.PUBLIC,
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlNamedElement

/**
 * A UML 2.x activity edge — control flow or object flow between two activity
 * nodes. The optional [guard] is a boolean expression rendered as `[guard]`,
 * and [isObjectFlow] flips between solid (control) and dashed (object) lines.
 */
@Serializable
data class UmlActivityEdge(
    override val id: String,
    val sourceId: String,
    val targetId: String,
    val guard: String? = null,
    val isObjectFlow: Boolean = false,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlRelationship

// ── Timing ───────────────────────────────────────────────────────────────────

/**
 * A UML 2.x timing-diagram lifeline — a participant whose state changes are
 * tracked over time. The [states] list is the discrete set of state values
 * the lifeline can take.
 */
@Serializable
data class UmlTimingLifeline(
    override val id: String,
    override val name: String,
    val states: List<String> = emptyList(),
    val timeline: List<UmlTimingTick> = emptyList(),
    override val visibility: Visibility = Visibility.PUBLIC,
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlNamedElement

/**
 * A point on a [UmlTimingLifeline]'s timeline. Each tick records the
 * lifeline's state at time [t]. The rendering joins consecutive ticks with
 * a step-line.
 */
@Serializable
data class UmlTimingTick(
    val t: Int,
    val state: String,
)

// ── Interaction Overview ─────────────────────────────────────────────────────

/** Kind discriminator for [UmlInteractionOverviewFrame] variants. */
@Serializable
public enum class UmlInteractionFrameKind {
    /** An inline interaction snippet (rounded rectangle with `ref` label). */
    INTERACTION_REF,

    /** A control-flow decision point (diamond). */
    DECISION,

    /** A control-flow merge point. */
    MERGE,

    /** Initial pseudo-node. */
    INITIAL,

    /** Final node. */
    FINAL,
}

/**
 * A node in a UML 2.x interaction-overview diagram. Mixes activity-style
 * control flow (initial / final / decision) with `ref InteractionName` frames
 * that point at full sequence/communication diagrams elsewhere.
 */
@Serializable
data class UmlInteractionOverviewFrame(
    override val id: String,
    override val name: String,
    val kind: UmlInteractionFrameKind,
    val referencedInteractionId: String? = null,
    override val visibility: Visibility = Visibility.PUBLIC,
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlNamedElement
