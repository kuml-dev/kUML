package dev.kuml.core.ocl

import kotlinx.serialization.Serializable

@Serializable
public data class KumlValidationResult(
    val valid: Boolean,
    val violations: List<KumlViolation>,
)
