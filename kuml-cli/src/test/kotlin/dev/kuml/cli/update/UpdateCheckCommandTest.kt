package dev.kuml.cli.update

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.ExitCodes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

/**
 * Drives `kuml update check` through Clikt's `.test()` harness with a
 * [StubReleasesClient] in the place of the real GitHub client. Covers all
 * four exit-code outcomes plus the cache interaction.
 *
 * We assert via the `--json` payload (a documented contract on
 * [UpdateCheckCommand.CheckJson]) — the human-readable line evolves more
 * freely.
 *
 * `currentVersion` is injected via the test constructor, so the assertions
 * are independent of whatever `KumlVersion.version` reports in the test
 * environment.
 */
class UpdateCheckCommandTest :
    StringSpec({

        "stable update available → exit 10, JSON status='update-available'" {
            val tmpDir = Files.createTempDirectory("kuml-update-test-")
            val stub = StubReleasesClient(latest = release("v9999.0.0"))
            val result =
                parent(stub, tmpDir, currentVersion = "1.0.0")
                    .test(listOf("check", "--json"))

            result.statusCode shouldBe ExitCodes.UPDATE_AVAILABLE
            result.stdout shouldContain """"status":"update-available""""
            result.stdout shouldContain """"latest_tag":"v9999.0.0""""
            Files.exists(tmpDir.resolve("update.json")) shouldBe true
        }

        "already up to date → exit 0, JSON status='current'" {
            val tmpDir = Files.createTempDirectory("kuml-update-test-")
            val stub = StubReleasesClient(latest = release("v0.0.1"))
            val result =
                parent(stub, tmpDir, currentVersion = "1.0.0")
                    .test(listOf("check", "--json"))

            result.statusCode shouldBe 0
            result.stdout shouldContain """"status":"current""""
        }

        "pre-release available with --include-prereleases → exit 11" {
            val tmpDir = Files.createTempDirectory("kuml-update-test-")
            val stub =
                StubReleasesClient(
                    latest = release("v0.0.1"),
                    all =
                        listOf(
                            release("v9999.0.0-rc.1", isPreRelease = true),
                            release("v0.0.1"),
                        ),
                )
            val result =
                parent(stub, tmpDir, currentVersion = "1.0.0")
                    .test(listOf("check", "--include-prereleases", "--json"))

            result.statusCode shouldBe ExitCodes.PRERELEASE_AVAILABLE
            result.stdout shouldContain """"status":"prerelease-available""""
        }

        "online error with no cache → exit ONLINE_ERROR" {
            val tmpDir = Files.createTempDirectory("kuml-update-test-")
            val stub = StubReleasesClient(latestFailure = "DNS lookup failed")
            val result =
                parent(stub, tmpDir, currentVersion = "1.0.0")
                    .test(listOf("check", "--json"))

            result.statusCode shouldBe ExitCodes.ONLINE_ERROR
            result.stdout shouldContain """"status":"error""""
        }

        "online error with stale cache → falls back to cache (no online_error)" {
            val tmpDir = Files.createTempDirectory("kuml-update-test-")
            val cache = UpdateCache(path = tmpDir.resolve("update.json"))
            cache.write(release("v0.0.1"))

            val stub = StubReleasesClient(latestFailure = "timeout")
            // --no-cache forces an online attempt that fails → fallback to cache.
            val result =
                parent(stub, tmpDir, currentVersion = "1.0.0")
                    .test(listOf("check", "--no-cache", "--json"))

            result.statusCode shouldBe 0
            result.stdout shouldContain """"source":"cache""""
        }

        "--offline reads cache only, no network call" {
            val tmpDir = Files.createTempDirectory("kuml-update-test-")
            val cache = UpdateCache(path = tmpDir.resolve("update.json"))
            cache.write(release("v0.0.1"))

            val stub = StubReleasesClient(latestFailure = "should-not-be-called")
            val result =
                parent(stub, tmpDir, currentVersion = "1.0.0")
                    .test(listOf("check", "--offline", "--json"))

            result.statusCode shouldBe 0
            stub.calls shouldBe 0
        }

        "fresh cache prevents a redundant network call" {
            val tmpDir = Files.createTempDirectory("kuml-update-test-")
            val cache = UpdateCache(path = tmpDir.resolve("update.json"))
            cache.write(release("v0.0.1"))

            val stub = StubReleasesClient(latest = release("v0.0.1"))
            val result =
                parent(stub, tmpDir, currentVersion = "1.0.0")
                    .test(listOf("check", "--json"))

            result.statusCode shouldBe 0
            stub.calls shouldBe 0
        }

        "current version not parseable as SemVer → exit ONLINE_ERROR with error status" {
            val tmpDir = Files.createTempDirectory("kuml-update-test-")
            val stub = StubReleasesClient(latest = release("v0.4.0"))
            val result =
                parent(stub, tmpDir, currentVersion = "unknown")
                    .test(listOf("check", "--json"))

            result.statusCode shouldBe ExitCodes.ONLINE_ERROR
            result.stdout shouldContain """"status":"error""""
            result.stdout shouldContain "could not parse local version"
        }
    })

// ─────────────────────────────────────────────────────────────────────────────
// Test helpers.

private fun release(
    tag: String,
    isPreRelease: Boolean = false,
): ReleaseInfo =
    ReleaseInfo(
        tagName = tag,
        name = tag,
        isPreRelease = isPreRelease,
        isDraft = false,
        body = "notes for $tag",
        publishedAt = "2026-01-01T00:00:00Z",
        htmlUrl = "https://github.com/kuml-dev/kUML/releases/tag/$tag",
    )

private class StubReleasesClient(
    private val latest: ReleaseInfo? = null,
    private val all: List<ReleaseInfo>? = null,
    private val latestFailure: String? = null,
) : ReleasesClient {
    var calls: Int = 0
        private set

    override fun fetchLatest(): ReleasesClient.Result {
        calls++
        return when {
            latestFailure != null -> ReleasesClient.Result.Failure(latestFailure)
            latest != null -> ReleasesClient.Result.Ok(latest)
            else -> ReleasesClient.Result.Failure("stub: no release configured")
        }
    }

    override fun fetchAll(limit: Int): ReleasesClient.ListResult {
        calls++
        return when {
            all != null -> ReleasesClient.ListResult.Ok(all)
            latestFailure != null -> ReleasesClient.ListResult.Failure(latestFailure)
            latest != null -> ReleasesClient.ListResult.Ok(listOf(latest))
            else -> ReleasesClient.ListResult.Failure("stub: no release configured")
        }
    }
}

/**
 * Build a tiny parent command that hosts a single [UpdateCheckCommand] with
 * the test doubles wired in. We can't use the full [dev.kuml.cli.KumlCli]
 * tree because that drags in the script-compiler dependencies and the live
 * [HttpReleasesClient] — we want to keep the unit test focused.
 */
private class TestRoot(
    child: CliktCommand,
) : CliktCommand(name = "kuml") {
    init {
        subcommands(child)
    }

    override fun help(context: Context): String = "test harness"

    override fun run() = Unit
}

private fun parent(
    stub: ReleasesClient,
    tmpDir: java.nio.file.Path,
    currentVersion: String,
): CliktCommand {
    val check =
        UpdateCheckCommand(
            clientFactory = { stub },
            cacheFactory = { UpdateCache(path = tmpDir.resolve("update.json")) },
            currentVersion = { currentVersion },
        )
    return TestRoot(check)
}
