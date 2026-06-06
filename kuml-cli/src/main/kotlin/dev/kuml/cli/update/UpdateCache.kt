package dev.kuml.cli.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * On-disk cache of the most recent `releases/latest` GitHub response so we
 * don't issue an HTTP request on every CLI invocation.
 *
 * Default location: `~/.cache/kuml/update.json` — XDG-compatible on Linux,
 * harmless on macOS/Windows (no platform convention for CLI caches there).
 * The path is overridable so tests can point at a `tempfile`.
 *
 * TTL is enforced by the caller via [isFresh] — the cache itself doesn't
 * know what "stale" means, that's a policy decision.
 */
internal class UpdateCache(
    private val path: Path = defaultPath(),
    private val clock: Clock = Clock.systemUTC(),
) {
    @Serializable
    internal data class Entry(
        /** Wall-clock instant of the fetch, ISO-8601. */
        @SerialName("fetched_at") val fetchedAt: String,
        /** The release payload as returned by GitHub. */
        val release: ReleaseInfo,
    )

    /** Read the cache, or `null` if the file is missing / unreadable / unparseable. */
    fun read(): Entry? {
        if (!Files.exists(path)) return null
        return runCatching {
            val raw = Files.readString(path)
            ReleaseInfo.JSON.decodeFromString<Entry>(raw)
        }.getOrNull()
    }

    /** Write the latest release to the cache. Creates parent directories on demand. */
    fun write(release: ReleaseInfo) {
        Files.createDirectories(path.parent)
        val entry =
            Entry(
                fetchedAt = Instant.now(clock).toString(),
                release = release,
            )
        Files.writeString(path, ReleaseInfo.JSON.encodeToString(entry))
    }

    /** Compute the age of an existing cache entry. */
    fun age(entry: Entry): Duration {
        val fetchedAt = runCatching { Instant.parse(entry.fetchedAt) }.getOrNull() ?: return Duration.ZERO
        val now = Instant.now(clock)
        return if (now.isBefore(fetchedAt)) Duration.ZERO else Duration.between(fetchedAt, now)
    }

    /** True if the entry is younger than [ttl]. */
    fun isFresh(
        entry: Entry,
        ttl: Duration,
    ): Boolean = age(entry) < ttl

    /** Path used by this instance — exposed for diagnostics / tests. */
    fun path(): Path = path

    companion object {
        /** Default TTL: 24h — long enough to be invisible, short enough to catch fresh releases. */
        val DEFAULT_TTL: Duration = Duration.ofHours(24)

        private fun defaultPath(): Path {
            // Honour XDG_CACHE_HOME on Linux. Fall back to `~/.cache` everywhere else —
            // not idiomatic on macOS/Windows but predictable and avoids platform-specific
            // logic for a CLI cache that's harmless to put anywhere.
            val xdg = System.getenv("XDG_CACHE_HOME")
            val base =
                if (!xdg.isNullOrBlank()) {
                    Path.of(xdg)
                } else {
                    Path.of(System.getProperty("user.home"), ".cache")
                }
            return base.resolve("kuml").resolve("update.json")
        }
    }
}
