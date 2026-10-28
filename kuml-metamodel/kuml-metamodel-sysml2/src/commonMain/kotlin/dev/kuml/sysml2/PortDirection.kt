package dev.kuml.sysml2

import kotlinx.serialization.Serializable

/** Direction of a SysML 2 port or flow property (`in` / `out` / `inout`). */
@Serializable
enum class PortDirection {
    In,
    Out,
    InOut,
}
