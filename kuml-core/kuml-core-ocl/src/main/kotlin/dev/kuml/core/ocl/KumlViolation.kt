package dev.kuml.core.ocl

import kotlinx.serialization.Serializable

@Serializable
public data class KumlViolation(
    val constraintId: String,
    val constraintName: String,
    val classifierId: String,
    val classifierName: String,
    val oclExpression: String,
    val message: String,
)
