package dev.kuml.blueprint.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/** Role an [Actor] plays in the journey. */
@Serializable
enum class ActorRole { CUSTOMER, STAFF, SYSTEM, PARTNER }

/**
 * An actor that participates in the journey — the customer who walks through it,
 * the staff who serve it, or the systems/partners that support it.
 *
 * V3.1.21
 */
@Serializable
data class Actor(
    override val id: String,
    override val name: String?,
    val role: ActorRole = ActorRole.CUSTOMER,
    val description: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BlueprintElement
