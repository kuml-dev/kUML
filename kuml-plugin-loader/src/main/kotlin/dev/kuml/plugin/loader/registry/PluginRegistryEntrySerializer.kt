package dev.kuml.plugin.loader.registry

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Custom KSerializer for [PluginRegistryEntry] that provides backward compatibility
 * with the legacy `signaturePublicKey` (single String) field while reading/writing
 * the new `signingKeys` (List<PluginSigningKey>) field.
 *
 * ## Deserialization behaviour
 * - If `signingKeys` is present and non-empty → use it directly.
 * - Else if legacy `signaturePublicKey` is non-blank → wrap it as a single
 *   `PluginSigningKey(publicKey=…, keyId="legacy", validFrom="1970-01-01",
 *   status=ACTIVE)` with no expiry.
 * - Else → `signingKeys = emptyList()`.
 *
 * ## Serialization behaviour
 * Always emits `signingKeys`; never emits the legacy `signaturePublicKey`.
 */
internal object PluginRegistryEntrySerializer : KSerializer<PluginRegistryEntry> {
    /** Surrogate that can carry both old and new wire fields. */
    @Serializable
    private data class Surrogate(
        val id: String,
        val category: String,
        val name: String,
        val version: String,
        val kumlVersionRange: String = "",
        val manifest: String,
        val downloads: String,
        // New key-rotation field (V3.1.14)
        val signingKeys: List<PluginSigningKey> = emptyList(),
        // Legacy field — only present in old registry JSON; never written on output
        val signaturePublicKey: String? = null,
        val maintainer: String = "",
        val homepage: String = "",
        val downloadCount: Long = 0,
        val rating: Double? = null,
        val ratingCount: Int = 0,
        val reviews: List<PluginReview> = emptyList(),
        val screenshotUrls: List<String> = emptyList(),
    )

    private val surrogateSerializer = Surrogate.serializer()

    override val descriptor: SerialDescriptor = surrogateSerializer.descriptor

    override fun serialize(
        encoder: Encoder,
        value: PluginRegistryEntry,
    ) {
        val surrogate =
            Surrogate(
                id = value.id,
                category = value.category,
                name = value.name,
                version = value.version,
                kumlVersionRange = value.kumlVersionRange,
                manifest = value.manifest,
                downloads = value.downloads,
                signingKeys = value.signingKeys,
                // signaturePublicKey intentionally omitted — always write new format
                maintainer = value.maintainer,
                homepage = value.homepage,
                downloadCount = value.downloadCount,
                rating = value.rating,
                ratingCount = value.ratingCount,
                reviews = value.reviews,
                screenshotUrls = value.screenshotUrls,
            )
        encoder.encodeSerializableValue(surrogateSerializer, surrogate)
    }

    override fun deserialize(decoder: Decoder): PluginRegistryEntry {
        val s = decoder.decodeSerializableValue(surrogateSerializer)

        // Key migration: prefer new list; fall back to legacy single-key field
        val resolvedKeys =
            when {
                s.signingKeys.isNotEmpty() -> s.signingKeys
                !s.signaturePublicKey.isNullOrBlank() ->
                    listOf(
                        PluginSigningKey(
                            publicKey = s.signaturePublicKey,
                            keyId = "legacy",
                            validFrom = "1970-01-01",
                            validUntil = null,
                            status = KeyStatus.ACTIVE,
                        ),
                    )
                else -> emptyList()
            }

        return PluginRegistryEntry(
            id = s.id,
            category = s.category,
            name = s.name,
            version = s.version,
            kumlVersionRange = s.kumlVersionRange,
            manifest = s.manifest,
            downloads = s.downloads,
            signingKeys = resolvedKeys,
            maintainer = s.maintainer,
            homepage = s.homepage,
            downloadCount = s.downloadCount,
            rating = s.rating,
            ratingCount = s.ratingCount,
            reviews = s.reviews,
            screenshotUrls = s.screenshotUrls,
        )
    }
}
