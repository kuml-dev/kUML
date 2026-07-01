package dev.kuml.core.model

import kotlinx.serialization.Polymorphic

/**
 * A named element within a namespace.
 *
 * Like [KumlElement], this is an open polymorphic base — see [KumlElement]
 * KDoc for the rationale and the `SerializersModule` registration mechanism.
 */
@Polymorphic
interface KumlNamespaceMember : KumlElement {
    val name: String
}
