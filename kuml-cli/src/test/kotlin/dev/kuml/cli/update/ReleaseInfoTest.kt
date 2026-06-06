package dev.kuml.cli.update

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Sanity-checks the GitHub Releases JSON-payload parsing against
 * a captured-real-response fixture.
 *
 * The fixture is intentionally **trimmed** to ~20 fields so the test stays
 * readable; the live response is ~80 fields. The `ignoreUnknownKeys = true`
 * setting on [ReleaseInfo.JSON] is what makes the trim safe.
 */
class ReleaseInfoTest :
    StringSpec({

        val singleReleaseFixture =
            """
            {
              "url": "https://api.github.com/repos/kuml-dev/kUML/releases/123456",
              "html_url": "https://github.com/kuml-dev/kUML/releases/tag/v0.4.0",
              "id": 123456,
              "tag_name": "v0.4.0",
              "name": "v0.4.0",
              "draft": false,
              "prerelease": false,
              "published_at": "2026-06-06T11:23:45Z",
              "body": "## Highlights\n- V1.1.9 Gradle plugin\n- V1.1.10 IntelliJ plugin\n"
            }
            """.trimIndent()

        "parses the single-release shape" {
            val info = ReleaseInfo.JSON.decodeFromString<ReleaseInfo>(singleReleaseFixture)
            info.tagName shouldBe "v0.4.0"
            info.name shouldBe "v0.4.0"
            info.isPreRelease shouldBe false
            info.isDraft shouldBe false
            info.publishedAt shouldBe "2026-06-06T11:23:45Z"
            info.htmlUrl shouldBe "https://github.com/kuml-dev/kUML/releases/tag/v0.4.0"
            info.body.contains("Gradle plugin") shouldBe true
        }

        "ignores unknown fields so GitHub schema additions don't break us" {
            val withExtra =
                """
                {
                  "tag_name": "v0.5.0",
                  "draft": false,
                  "prerelease": false,
                  "body": "",
                  "future_field_we_dont_know_about": { "nested": [1, 2, 3] },
                  "another_unknown": null
                }
                """.trimIndent()
            val info = ReleaseInfo.JSON.decodeFromString<ReleaseInfo>(withExtra)
            info.tagName shouldBe "v0.5.0"
        }

        "exposes a parsed SemVer for known-good tags" {
            val info = ReleaseInfo.JSON.decodeFromString<ReleaseInfo>(singleReleaseFixture)
            info.semver shouldBe SemVer(0, 4, 0)
        }

        "returns null SemVer for non-semver tags (no crash, the caller decides)" {
            val noisy =
                """
                {
                  "tag_name": "snapshot-build-from-internal-ci",
                  "draft": false,
                  "prerelease": false,
                  "body": ""
                }
                """.trimIndent()
            val info = ReleaseInfo.JSON.decodeFromString<ReleaseInfo>(noisy)
            info.semver shouldBe null
        }

        "parses a list response (GET /releases) of multiple entries" {
            val listJson =
                """
                [
                  {"tag_name": "v0.5.0-rc.1", "draft": false, "prerelease": true,  "body": ""},
                  {"tag_name": "v0.4.0",      "draft": false, "prerelease": false, "body": ""},
                  {"tag_name": "v0.3.0",      "draft": false, "prerelease": false, "body": ""}
                ]
                """.trimIndent()
            val releases = ReleaseInfo.JSON.decodeFromString<List<ReleaseInfo>>(listJson)
            releases shouldHaveSize 3
            releases[0].isPreRelease shouldBe true
            releases[1].tagName shouldBe "v0.4.0"
        }
    })
