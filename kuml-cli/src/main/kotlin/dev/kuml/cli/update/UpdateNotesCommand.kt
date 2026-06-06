package dev.kuml.cli.update

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.kuml.cli.ExitCodes

/**
 * `kuml update notes` — print the release-notes body of the latest (or a
 * specific) version.
 *
 * Uses the same cache as `kuml update check`, so a regular `check` followed
 * by `notes` doesn't hit the network twice.
 *
 *   - `kuml update notes`                  → notes for the latest release
 *   - `kuml update notes --target=v0.4.0`  → notes for that specific tag
 *   - `kuml update notes --offline`        → cache-only (latest only)
 *
 * Output is raw markdown by design — we don't try to ANSI-format inline. Tools
 * like `glow` or `bat` do this better; piping is the unix way.
 */
internal class UpdateNotesCommand internal constructor(
    private val clientFactory: () -> ReleasesClient = { HttpReleasesClient() },
    private val cacheFactory: () -> UpdateCache = { UpdateCache() },
) : CliktCommand(name = "notes") {
    private fun warn(msg: String) = echo(msg, err = true)

    private val target by option(
        "--target",
        help = "Tag of a specific release to fetch notes for (e.g. 'v0.4.0'). Default: latest.",
    )
    private val offline by option("--offline", help = "Use the cached release only; do not touch the network.").flag(default = false)
    private val noCache by option("--no-cache", help = "Bypass the cache; force a fresh fetch.").flag(default = false)

    override fun help(context: Context): String = "Show the release-notes markdown for the latest (or a specific) kUML release."

    override fun run() {
        val release = resolveRelease()
        if (release == null) {
            throw ProgramResult(ExitCodes.ONLINE_ERROR)
        }

        val header =
            buildString {
                append("# ").append(release.name ?: release.tagName)
                release.publishedAt?.let { append("  _(published ").append(it).append(")_") }
            }
        echo(header)
        echo("")
        if (release.body.isBlank()) {
            echo("_(no release notes provided for ${release.tagName})_")
        } else {
            echo(release.body)
        }
        release.htmlUrl?.let {
            echo("")
            echo("Source: $it")
        }
    }

    private fun resolveRelease(): ReleaseInfo? {
        val cache = cacheFactory()
        val cached = cache.read()
        val targetTag = target

        // Path A: a specific tag was requested — cache only helps if it matches.
        if (targetTag != null) {
            if (offline) {
                warn("error: --target=$targetTag requires a network fetch (cache stores only the latest release).")
                return null
            }
            return when (val r = clientFactory().fetchAll(limit = 100)) {
                is ReleasesClient.ListResult.Ok -> {
                    val match = r.releases.firstOrNull { it.tagName == targetTag || it.tagName == "v$targetTag" }
                    if (match == null) {
                        warn("error: no release found with tag '$targetTag'.")
                    }
                    match
                }
                is ReleasesClient.ListResult.HttpError -> {
                    warn("error: HTTP ${r.statusCode} from GitHub while fetching releases.")
                    null
                }
                is ReleasesClient.ListResult.Failure -> {
                    warn("error: ${r.message}")
                    null
                }
            }
        }

        // Path B: latest. Use the cache if fresh, otherwise fetch + persist.
        if (!noCache && cached != null && cache.isFresh(cached, UpdateCache.DEFAULT_TTL)) {
            return cached.release
        }
        if (offline) {
            if (cached != null) return cached.release
            warn("error: no cached release available and --offline forbids network access.")
            return null
        }
        return when (val r = clientFactory().fetchLatest()) {
            is ReleasesClient.Result.Ok -> {
                runCatching { cache.write(r.release) }
                    .onFailure { warn("warning: could not persist update cache (${it.message})") }
                r.release
            }
            is ReleasesClient.Result.HttpError -> {
                warn("error: HTTP ${r.statusCode} from GitHub.")
                cached?.release // graceful fallback to stale cache
            }
            is ReleasesClient.Result.Failure -> {
                warn("error: ${r.message}")
                cached?.release
            }
        }
    }
}
