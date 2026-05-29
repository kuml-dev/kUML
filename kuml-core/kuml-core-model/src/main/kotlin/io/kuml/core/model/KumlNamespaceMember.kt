package io.kuml.core.model

/** Ein benanntes Element innerhalb eines Namespaces. */
interface KumlNamespaceMember : KumlElement {
    val name: String
}
