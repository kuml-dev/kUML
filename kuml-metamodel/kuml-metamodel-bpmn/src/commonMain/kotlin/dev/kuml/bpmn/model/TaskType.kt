package dev.kuml.bpmn.model

import kotlinx.serialization.Serializable

/** Marker type on a BPMN task, determining its visual icon and runtime semantics. */
@Serializable
enum class TaskType {
    NONE,
    USER,
    SERVICE,
    SEND,
    RECEIVE,
    MANUAL,
    SCRIPT,
    BUSINESS_RULE,
}
