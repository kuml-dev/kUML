package dev.kuml.profile

import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.TagValue
import kotlinx.serialization.Serializable

/**
 * Concrete implementation of [AppliedStereotype] for use by the kUML profile system.
 *
 * This is the only concrete type in V1.1. It lives in `kuml-profile-api` and
 * implements the interface from `kuml-metamodel-uml`, keeping the
 * dependency direction strictly one-way:
 * `kuml-profile-api` → `kuml-metamodel-uml`.
 *
 * Tagged values are stored as [TagValue] for serialization compatibility.
 * Use [toTagValue] to convert arbitrary `Any?` values.
 */
@Serializable
public data class KumlStereotypeApplication(
    override val profileNamespace: String,
    override val stereotypeName: String,
    override val tags: Map<String, TagValue> = emptyMap(),
) : AppliedStereotype {
    init {
        require(profileNamespace.isNotBlank()) {
            "KumlStereotypeApplication.profileNamespace must not be blank"
        }
        require(stereotypeName.isNotBlank()) {
            "KumlStereotypeApplication.stereotypeName must not be blank"
        }
    }
}
