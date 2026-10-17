package dev.kuml.web.frontend

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Keeps the Playwright version of the browser regression test in lockstep.
 *
 * The npm package `@playwright/test` and the browsers inside the Docker image
 * `mcr.microsoft.com/playwright:v<version>-<distro>` must be the same Playwright version: each release
 * expects specific browser builds, and a mismatched npm version fails with "Executable doesn't exist"
 * inside the container. Dependabot bumps the npm side on its own, and its CI is green anyway, because the
 * container test is not part of CI. This test turns that silent drift into a red build, so the Dockerfiles
 * are bumped in the same change.
 */
class PlaywrightVersionSyncTest :
    FunSpec({
        // Gradle runs tests with the module directory as working directory; an IDE may start at the repo root.
        val moduleDir = listOf(File("."), File("kuml-web")).first { File(it, "package.json").isFile }

        val howToFix =
            "Bump `@playwright/test` in kuml-web/package.json (and regenerate package-lock.json) together with " +
                "the `mcr.microsoft.com/playwright` image tag in kuml-web/.delivery/*/Dockerfile: they must be the same " +
                "Playwright version."

        fun pinnedVersion(): String =
            Json
                .parseToJsonElement(File(moduleDir, "package.json").readText())
                .jsonObject["devDependencies"]!!
                .jsonObject["@playwright/test"]!!
                .jsonPrimitive.content

        fun imageVersion(dockerfile: String): String {
            val from =
                File(moduleDir, dockerfile)
                    .readLines()
                    .firstOrNull { it.trimStart().startsWith("FROM ") && "mcr.microsoft.com/playwright" in it }
                    ?: error("$dockerfile has no FROM line with the Playwright image")
            return Regex("""mcr\.microsoft\.com/playwright:v(\d+\.\d+\.\d+)""").find(from)?.groupValues?.get(1)
                ?: error("$dockerfile: cannot read the Playwright version from '$from'")
        }

        test("package.json pins @playwright/test to an exact version") {
            pinnedVersion() shouldMatch Regex("""\d+\.\d+\.\d+""")
        }

        test("package-lock.json resolves @playwright/test to the pinned version") {
            val lock = Json.parseToJsonElement(File(moduleDir, "package-lock.json").readText()).jsonObject
            val locked =
                (lock["packages"] as JsonObject)["node_modules/@playwright/test"]!!
                    .jsonObject["version"]!!
                    .jsonPrimitive.content
            withClue(howToFix) { locked shouldBe pinnedVersion() }
        }

        listOf(".delivery/development/Dockerfile", ".delivery/runtime/Dockerfile").forEach { dockerfile ->
            test("$dockerfile uses the Playwright image of the pinned @playwright/test version") {
                withClue("$dockerfile: $howToFix") { imageVersion(dockerfile) shouldBe pinnedVersion() }
            }
        }
    })
