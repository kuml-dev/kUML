package dev.kuml.cli.update

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class UpdateCacheTest :
    StringSpec({

        val sampleRelease =
            ReleaseInfo(
                tagName = "v0.4.0",
                name = "v0.4.0",
                isPreRelease = false,
                isDraft = false,
                body = "release-notes",
                publishedAt = "2026-06-06T11:23:45Z",
                htmlUrl = "https://github.com/kuml-dev/kUML/releases/tag/v0.4.0",
            )

        "read returns null when the cache file does not exist" {
            val tmpDir = Files.createTempDirectory("kuml-cache-test-")
            val cache = UpdateCache(path = tmpDir.resolve("missing.json"))
            cache.read().shouldBeNull()
        }

        "write then read round-trips a release entry" {
            val tmpDir = Files.createTempDirectory("kuml-cache-test-")
            val cache = UpdateCache(path = tmpDir.resolve("nested/update.json"))
            cache.write(sampleRelease)

            val entry = cache.read()
            entry.shouldNotBeNull()
            entry.release.tagName shouldBe "v0.4.0"
            // fetchedAt must be a parseable ISO-8601 instant.
            Instant.parse(entry.fetchedAt) // throws on failure
        }

        "isFresh returns true within TTL and false beyond it" {
            val tmpDir = Files.createTempDirectory("kuml-cache-test-")
            val fakeNow = Instant.parse("2026-06-06T12:00:00Z")
            val frozenClock = Clock.fixed(fakeNow, ZoneOffset.UTC)

            val cache = UpdateCache(path = tmpDir.resolve("u.json"), clock = frozenClock)
            cache.write(sampleRelease)
            val entry = cache.read()!!
            cache.isFresh(entry, Duration.ofHours(24)) shouldBe true

            // Move the clock forward 25h — entry is now stale.
            val laterCache =
                UpdateCache(
                    path = cache.path(),
                    clock = Clock.fixed(fakeNow.plus(Duration.ofHours(25)), ZoneOffset.UTC),
                )
            val rereadEntry = laterCache.read()!!
            laterCache.isFresh(rereadEntry, Duration.ofHours(24)) shouldBe false
        }

        "read returns null for corrupted JSON instead of throwing" {
            val tmpDir = Files.createTempDirectory("kuml-cache-test-")
            val path = tmpDir.resolve("u.json")
            Files.writeString(path, "{ this is not valid json")
            val cache = UpdateCache(path = path)
            cache.read().shouldBeNull()
        }

        "age never goes negative if the cache claims a future fetch time" {
            // Defensive: if the cache JSON has a clock-skewed future timestamp,
            // we still want a sane age (0) rather than a negative duration that
            // would unexpectedly make `isFresh(.., tiny-ttl)` succeed.
            val tmpDir = Files.createTempDirectory("kuml-cache-test-")
            val now = Instant.parse("2026-06-06T12:00:00Z")
            val cache = UpdateCache(path = tmpDir.resolve("u.json"), clock = Clock.fixed(now, ZoneOffset.UTC))
            val futureEntry =
                UpdateCache.Entry(
                    fetchedAt = now.plus(Duration.ofDays(10)).toString(),
                    release = sampleRelease,
                )
            cache.age(futureEntry) shouldBe Duration.ZERO
        }
    })
