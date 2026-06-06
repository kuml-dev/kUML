package dev.kuml.cli.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Subset of GitHub's `/repos/{owner}/{repo}/releases/latest` response that the
 * `kuml update` flow actually consumes. Extra fields in the live response are
 * ignored (`Json { ignoreUnknownKeys = true }`) — the schema is huge, we only
 * need four fields.
 *
 * The same shape covers `/releases` (list response) — each entry is a
 * `ReleaseInfo`.
 */
@Serializable
internal data class ReleaseInfo(
    /** Git tag of the release, e.g. `"v0.4.0"`. */
    @SerialName("tag_name") val tagName: String,
    /** Human-readable release title — usually identical to `tag_name`. */
    val name: String? = null,
    /** Whether GitHub marks the release as a pre-release (independent of SemVer suffix). */
    @SerialName("prerelease") val isPreRelease: Boolean = false,
    /** Whether the release is published or still a draft. Drafts are skipped. */
    @SerialName("draft") val isDraft: Boolean = false,
    /** Markdown release-notes body. May be empty for tag-only releases. */
    val body: String = "",
    /** ISO-8601 publish timestamp. Optional — drafts don't have one. */
    @SerialName("published_at") val publishedAt: String? = null,
    /** Browser URL — handy for the user-facing hint ("see the release at …"). */
    @SerialName("html_url") val htmlUrl: String? = null,
) {
    /** Parsed SemVer of [tagName], or `null` if the tag isn't a valid SemVer. */
    val semver: SemVer? get() = SemVer.parseOrNull(tagName)

    companion object {
        /**
         * The shared JSON codec for both the live GitHub response and the
         * `~/.cache/kuml/update.json` cache file.
         *
         * `ignoreUnknownKeys = true` is essential — the GitHub API adds
         * fields over time and we'd rather not break on every schema bump.
         */
        val JSON: Json =
            Json {
                ignoreUnknownKeys = true
                prettyPrint = false
                encodeDefaults = true
            }
    }
}
