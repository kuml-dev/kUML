package dev.kuml.packaging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Regression test for a code-review finding on `feat/validate-named-arguments`
 * (2026-08): kuml-cli's shadowJar-based distributions — the
 * `ghcr.io/kuml-dev/kuml-cli` Docker image, `.deb`, `.rpm` (and, as it turns
 * out, the unsigned DMG/MSI built here too) — never saw
 * `:kuml-style-worker`'s runtime jars. Shadow's default `runtimeClasspath`
 * packaging deliberately excludes `styleWorkerRuntime` (see the
 * classpath-collision rationale in
 * `dev.kuml.core.script.style.NamedArgumentStyleCheck`'s KDoc), and a single
 * fat jar has no `lib/` directory for a sibling `lib/style/` to live in — so
 * `kuml validate`'s style check silently degraded to
 * `STYLE_CHECK_UNAVAILABLE` on every call inside those distributions, with
 * no test catching it (`kuml-cli`'s own `ValidateCommandStyleTest` only
 * exercises the installDist-style `-Dkuml.style.lib` wiring, never the real
 * shadow jar).
 *
 * The fix: `:kuml-cli:copyStyleWorkerLibForShadowJar` (see
 * `kuml-cli/build.gradle.kts`) stages the same jars into `build/libs/style/`,
 * right next to the shadow jar — `jpackage --input` (packageDeb/Rpm/Dmg/Msi)
 * picks that up automatically, and the Dockerfile now `COPY`s it explicitly.
 *
 * This test builds the real shadow jar plus that sibling `style/` directory
 * (wired as a dependency of this module's `test` task in build.gradle.kts)
 * and launches it exactly the way the Docker image does — `java -jar
 * <shadow jar>` with `style/` sitting next to it in the same directory — to
 * prove the style check actually runs end-to-end in that layout, not just
 * via `installDist`.
 */
class ShadowJarStyleCheckTest :
    StringSpec({

        "kuml validate finds the style-worker lib next to the shadow JAR (Docker/.deb/.rpm layout)" {
            val shadowJarPath = System.getProperty("kuml.packaging.shadowJarPath")
            requireNotNull(shadowJarPath) {
                "-Dkuml.packaging.shadowJarPath not set — check the tasks.withType<Test> wiring in build.gradle.kts"
            }
            val shadowJar = File(shadowJarPath)
            shadowJar.isFile.shouldBeTrue()

            // The exact regression: without this sibling directory, StyleWorkerLibLocator
            // finds nothing and the check silently degrades to Unavailable.
            File(shadowJar.parentFile, "style").isDirectory.shouldBeTrue()

            // Tests run with CWD = kuml-packaging/; walk up to the repo root, same
            // convention as PackagingTest's workflow-file lookups.
            val fixture =
                File("")
                    .absoluteFile
                    .parentFile // kUML/
                    .resolve("kuml-cli/src/test/resources/style/positional.kuml.kts")
            fixture.isFile.shouldBeTrue()

            val process =
                ProcessBuilder("java", "-jar", shadowJar.absolutePath, "validate", fixture.absolutePath)
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            val finishedInTime = process.waitFor(60, TimeUnit.SECONDS)
            finishedInTime.shouldBeTrue()

            // What the bug actually looked like: exit 0 and a
            // STYLE_CHECK_UNAVAILABLE warning instead of a real finding.
            output shouldNotContain "STYLE_CHECK_UNAVAILABLE"
            output shouldContain "Style validation:"
            output shouldContain "POSITIONAL_ARGUMENT"
            // ExitCodes.VALIDATION_VIOLATIONS in :kuml-cli — hardcoded here since
            // :kuml-packaging deliberately has no compile dependency on :kuml-cli.
            process.exitValue() shouldBe 5
        }
    })
