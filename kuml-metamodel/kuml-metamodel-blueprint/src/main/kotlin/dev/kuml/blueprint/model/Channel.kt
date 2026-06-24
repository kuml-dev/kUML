package dev.kuml.blueprint.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * Channel kind — determines the touchpoint icon in the renderer.
 *
 * V3.1.21
 */
@Serializable
enum class ChannelKind { WEB, APP, PHONE, EMAIL, IN_PERSON, MAIL, SOCIAL, CHAT, OTHER }

/**
 * A communication channel through which a touchpoint happens (website, app, phone, …).
 *
 * V3.1.21
 */
@Serializable
data class Channel(
    override val id: String,
    override val name: String?,
    val kind: ChannelKind = ChannelKind.OTHER,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BlueprintElement
