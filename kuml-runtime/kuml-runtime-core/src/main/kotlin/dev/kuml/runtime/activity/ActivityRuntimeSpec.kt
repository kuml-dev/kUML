package dev.kuml.runtime.activity

import dev.kuml.sysml2.ActivityNodeKind

/**
 * Model-agnostic spec for an activity to be executed. Both SysML 2 ACT
 * and (future) UML Activity producers fill this spec — the runtime
 * doesn't know which metamodel produced it.
 */
public data class ActivityRuntimeSpec(
    /** nodeId → spec */
    val nodes: Map<String, ActivityNodeSpec>,
    val edges: List<ActivityEdgeSpec>,
)

public data class ActivityNodeSpec(
    val id: String,
    val kind: ActivityNodeKind,
    /** Raw-string body for Action nodes; null for pseudo-nodes. */
    val actionBody: String? = null,
)

public data class ActivityEdgeSpec(
    val id: String,
    val sourceNodeId: String,
    val targetNodeId: String,
    /** Raw OCL-like guard string; null = unconditionally true. */
    val guard: String? = null,
    val isObjectFlow: Boolean = false,
    /** Type name for ObjectFlow edges; null for ControlFlow edges. */
    val objectType: String? = null,
)
