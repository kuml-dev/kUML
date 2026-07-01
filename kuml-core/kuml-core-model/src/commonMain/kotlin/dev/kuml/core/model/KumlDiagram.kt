package dev.kuml.core.model

import kotlinx.serialization.Serializable

/**
 * A single diagram within a kUML model.
 *
 * @property name Human-readable name of the diagram.
 * @property type The diagram type (determines renderer and DSL elements).
 * @property elements Child elements defined inside this diagram.
 * @property id Stable identifier derived from the diagram name.
 *   Phase 1 introduces the full deterministic ID strategy via UmlIds.
 * @property metadata Arbitrary additional metadata.
 *
 * [elements] is typed as the open, [kotlinx.serialization.Polymorphic]
 * [KumlElement] base. Decoding a `KumlDiagram` from JSON requires a `Json`
 * instance configured with a `SerializersModule` that registers the
 * concrete element subtypes for the languages in use (e.g.
 * `dev.kuml.uml.UmlSerializersModule` for UML). Because [type] is itself a
 * field named `type`, callers must NOT use kotlinx's default class
 * discriminator key (`"type"`) for the polymorphic wrapper — configure
 * `classDiscriminator = "@type"` (or similar) on the `Json` instance,
 * otherwise encoding/decoding collides with this field.
 */
@Serializable
data class KumlDiagram(
    override val name: String,
    val type: DiagramType = DiagramType.CLASS,
    val elements: List<KumlElement> = emptyList(),
    override val id: String = name,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
    val config: DiagramConfig? = null,
) : KumlNamespaceMember
