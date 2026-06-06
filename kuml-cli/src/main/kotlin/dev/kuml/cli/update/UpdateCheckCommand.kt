package dev.kuml.cli.update

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.kuml.cli.ExitCodes
import dev.kuml.cli.KumlVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration

/**
 * `kuml update check` — does a newer release exist on GitHub?
 *
 * Exit codes (the *contract* for scripts/CI):
 *   - **0**   : on the latest stable release.
 *   - **10**  : a newer **stable** release is available (see [ExitCodes.UPDATE_AVAILABLE]).
 *   - **11**  : a newer **pre-release** is available, with no newer stable
 *               (see [ExitCodes.PRERELEASE_AVAILABLE]). Only triggered when
 *               `--include-prereleases` is passed.
 *   - **1**   : online failure with no usable cache fallback
 *               (see [ExitCodes.ONLINE_ERROR]).
 *
 * Output:
 *   - default        : one-line human-readable status line on stdout.
 *   - `--json`       : single-line JSON payload (see [CheckJson]), stable contract.
 *
 * Caching:
 *   - The latest GitHub response is cached at `~/.cache/kuml/update.json`
 *     with a 24-hour TTL.
 *   - `--no-cache` forces a fresh fetch and overwrites the cache.
 *   - `--offline` reads the cache only — never touches the network. Useful in
 *     CI matrices where the GitHub API would rate-limit.
 *
 * Privacy: the HTTP request carries a `User-Agent: kuml-cli`. No telemetry,
 * no install ID, no opt-in tracking. The whole flow is opt-in (the user runs
 * the command) and there is no auto-trigger on other `kuml` invocations in
 * V2.0.1 — see the auto-hint plan in [[kUML V2.0]].
 */
internal class UpdateCheckCommand internal constructor(
    private val clientFactory: () -> ReleasesClient = { HttpReleasesClient() },
    private val cacheFactory: () -> UpdateCache = { UpdateCache() },
    /**
     * The version to compare against. Injected so tests are independent of
     * the resource-loaded `KumlVersion.version` (which is `"unknown"` in
     * environments where `processResources` hasn't generated the properties
     * file yet — e.g. running tests from inside an IDE without a Gradle sync).
     */
    private val currentVersion: () -> String = { KumlVersion.version },
) : CliktCommand(name = "check") {
    /** Emit a warning to stderr via Clikt's echo (so it ends up on the right channel under test). */
    private fun warn(msg: String) = echo(msg, err = true)

    private val json by option("--json", help = "Emit the check result as a single-line JSON object.").flag(default = false)
    private val noCache by option("--no-cache", help = "Force a fresh GitHub fetch, ignoring the local cache.").flag(default = false)
    private val offline by option("--offline", help = "Read the cache only; do not touch the network.").flag(default = false)
    private val includePrereleases by option(
        "--include-prereleases",
        help = "Compare against the most recent release including pre-releases.",
    ).flag(default = false)

    override fun help(context: Context): String =
        "Check GitHub for a newer kUML release (exit 0=current, 10=update, 11=pre-release, 1=online error)."

    override fun run() {
        val currentRaw = currentVersion()
        val current = SemVer.parseOrNull(currentRaw)
        if (current == null) {
            // The build couldn't determine its own version. Don't pretend to know
            // whether we're up to date — that's an online error class outcome.
            emit(
                CheckJson(
                    status = "error",
                    current = currentRaw,
                    error = "could not parse local version '$currentRaw' as SemVer",
                ),
            )
            throw ProgramResult(ExitCodes.ONLINE_ERROR)
        }

        val cache = cacheFactory()
        val cached = cache.read()

        val release = resolveRelease(cache, cached)
        // `resolveRelease` either returns a usable release or throws ProgramResult.
        val latest = release.semver
        if (latest == null) {
            emit(
                CheckJson(
                    status = "error",
                    current = current.toString(),
                    latestTag = release.tagName,
                    error = "GitHub release tag '${release.tagName}' is not valid SemVer",
                ),
            )
            throw ProgramResult(ExitCodes.ONLINE_ERROR)
        }

        // If the caller opted *out* of pre-releases (the default) but the
        // latest release on GitHub is one, we want to report against the most
        // recent *stable* tag instead — but `releases/latest` already excludes
        // pre-releases by GitHub's own rules, so this is only reachable when
        // `--include-prereleases` flipped the discovery to `fetchAll`.
        val isPrerelease = release.isPreRelease || latest.isPreRelease
        val cmp = current.compareTo(latest)

        when {
            cmp >= 0 -> {
                emit(
                    CheckJson(
                        status = "current",
                        current = current.toString(),
                        latestTag = release.tagName,
                        htmlUrl = release.htmlUrl,
                        isPreRelease = isPrerelease,
                        source = sourceLabel(cached, release),
                    ),
                )
                // Exit 0 implicitly — `ProgramResult(0)` is unnecessary.
            }
            isPrerelease -> {
                emit(
                    CheckJson(
                        status = "prerelease-available",
                        current = current.toString(),
                        latestTag = release.tagName,
                        htmlUrl = release.htmlUrl,
                        isPreRelease = true,
                        source = sourceLabel(cached, release),
                    ),
                )
                throw ProgramResult(ExitCodes.PRERELEASE_AVAILABLE)
            }
            else -> {
                emit(
                    CheckJson(
                        status = "update-available",
                        current = current.toString(),
                        latestTag = release.tagName,
                        htmlUrl = release.htmlUrl,
                        isPreRelease = false,
                        source = sourceLabel(cached, release),
                    ),
                )
                throw ProgramResult(ExitCodes.UPDATE_AVAILABLE)
            }
        }
    }

    /**
     * Resolve the release to compare against, honouring `--offline` / `--no-cache`
     * and the 24h cache TTL.
     *
     * Throws `ProgramResult(ONLINE_ERROR)` (with a structured error message on
     * the chosen output channel) if no release can be obtained.
     */
    private fun resolveRelease(
        cache: UpdateCache,
        cached: UpdateCache.Entry?,
    ): ReleaseInfo {
        // 1. `--offline`: cache-only. If the cache is missing, that's a hard error.
        if (offline) {
            if (cached != null) return cached.release
            emit(
                CheckJson(
                    status = "error",
                    current = currentVersion(),
                    error = "no cached release available at ${cache.path()} and --offline forbids network access",
                ),
            )
            throw ProgramResult(ExitCodes.ONLINE_ERROR)
        }

        // 2. Cache is fresh enough and the caller didn't pass `--no-cache`.
        if (!noCache && cached != null && cache.isFresh(cached, UpdateCache.DEFAULT_TTL)) {
            return cached.release
        }

        // 3. Online fetch — happy path.
        val client = clientFactory()
        val result =
            if (includePrereleases) {
                // `/releases/latest` skips pre-releases on GitHub's side. Pull the
                // first page and pick the top non-draft entry.
                when (val r = client.fetchAll(limit = 10)) {
                    is ReleasesClient.ListResult.Ok ->
                        r.releases
                            .firstOrNull { !it.isDraft }
                            ?.let { ReleasesClient.Result.Ok(it) }
                            ?: ReleasesClient.Result.Failure("no published releases found")
                    is ReleasesClient.ListResult.HttpError -> ReleasesClient.Result.HttpError(r.statusCode, r.body)
                    is ReleasesClient.ListResult.Failure -> ReleasesClient.Result.Failure(r.message, r.cause)
                }
            } else {
                client.fetchLatest()
            }

        return when (result) {
            is ReleasesClient.Result.Ok -> {
                runCatching { cache.write(result.release) }
                    .onFailure { warn("warning: could not persist update cache (${it.message})") }
                result.release
            }
            else -> {
                // Online failed — fall back to a stale cache if we have one. That's
                // the friendlier behaviour for users on flaky networks. Distinguish
                // it in the JSON output via `source = "cache-stale"`.
                if (cached != null) {
                    warn("warning: GitHub fetch failed (${describeFailure(result)}), using stale cache from ${cached.fetchedAt}")
                    cached.release
                } else {
                    emit(
                        CheckJson(
                            status = "error",
                            current = currentVersion(),
                            error = describeFailure(result),
                        ),
                    )
                    throw ProgramResult(ExitCodes.ONLINE_ERROR)
                }
            }
        }
    }

    private fun describeFailure(result: ReleasesClient.Result): String =
        when (result) {
            is ReleasesClient.Result.HttpError -> "HTTP ${result.statusCode} from GitHub"
            is ReleasesClient.Result.Failure -> "online fetch failed: ${result.message}"
            is ReleasesClient.Result.Ok -> "ok" // unreachable but the compiler insists
        }

    private fun sourceLabel(
        cached: UpdateCache.Entry?,
        chosen: ReleaseInfo,
    ): String =
        when {
            cached != null && cached.release.tagName == chosen.tagName -> "cache"
            else -> "live"
        }

    private fun emit(payload: CheckJson) {
        if (json) {
            echo(CHECK_JSON.encodeToString(CheckJson.serializer(), payload))
        } else {
            echo(payload.toHumanLine())
        }
    }

    /**
     * Structured `--json` payload. **This is a public CLI contract** — extending
     * it with new fields is fine (consumers ignore unknowns), but renaming or
     * removing one is a breaking change.
     */
    @Serializable
    internal data class CheckJson(
        /** One of: `current`, `update-available`, `prerelease-available`, `error`. */
        val status: String,
        /** Currently installed version, as reported by `KumlVersion.version`. */
        val current: String,
        @SerialName("latest_tag") val latestTag: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
        @SerialName("is_prerelease") val isPreRelease: Boolean = false,
        /** `"live"` or `"cache"` — where the comparison data came from. */
        val source: String? = null,
        /** Present only when `status == "error"`. */
        val error: String? = null,
    ) {
        fun toHumanLine(): String =
            when (status) {
                "current" -> "kuml $current is up to date (latest: $latestTag${sourceSuffix()})."
                "update-available" ->
                    "Update available: $current → $latestTag${sourceSuffix()}. " +
                        "Run 'kuml update notes' to see what changed."
                "prerelease-available" ->
                    "Pre-release available: $current → $latestTag${sourceSuffix()}. " +
                        "Run 'kuml update notes' to see what changed."
                "error" -> "error: ${error ?: "unknown error"}"
                else -> "status: $status"
            }

        private fun sourceSuffix(): String = source?.let { ", source: $it" } ?: ""
    }

    private companion object {
        val CHECK_JSON =
            Json {
                encodeDefaults = false
                prettyPrint = false
            }

        // Suppress IDE "unused" warning for the TTL constant exposed via UpdateCache.
        @Suppress("unused")
        val DEFAULT_TTL: Duration = UpdateCache.DEFAULT_TTL
    }
}
