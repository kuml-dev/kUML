package dev.kuml.packaging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * V2.0.32 — packaging smoke tests.
 *
 * These tests verify the static artefacts (Dockerfile, build script) are
 * correctly structured. The actual jpackage / Docker invocations
 * (packageDeb/Rpm/Dmg/Msi, dockerBuildCli) are OS-gated and run only in CI
 * via the release-installers.yml workflow — they are not part of the local
 * `check` task. [ShadowJarStyleCheckTest], in contrast, *does* run as part
 * of `check` (it only needs `java`, not jpackage or a Docker daemon) and
 * exercises the real shadow jar end-to-end.
 */
class PackagingTest :
    StringSpec({

        "build.gradle.kts declares all five distribution tasks" {
            val buildFile = File("build.gradle.kts")
            buildFile.exists().shouldBeTrue()
            val content = buildFile.readText()
            val expectedTasks = listOf("packageDeb", "packageRpm", "packageDmg", "packageMsi", "dockerBuildCli")
            expectedTasks.forEach { taskName ->
                content shouldContain taskName
            }
        }

        "every distribution task also depends on copyStyleWorkerLibForShadowJar" {
            // Regression guard for the Docker/.deb/.rpm style-check gap found in
            // code review of feat/validate-named-arguments: all five tasks build
            // the shadow jar via jpackage's --input / Docker's build context =
            // the shadow jar's directory, so the style-worker lib must be staged
            // as a SIBLING of the shadow jar for any of them to ship a working
            // `kuml validate` style check.
            val content = File("build.gradle.kts").readText()
            val occurrences =
                Regex("""dependsOn\(":kuml-cli:shadowJar", ":kuml-cli:copyStyleWorkerLibForShadowJar"\)""")
                    .findAll(content)
                    .count()
            // Once per distribution task (packageDeb/Rpm/Dmg/Msi, dockerBuildCli) plus
            // once more in the tasks.withType<Test> wiring that feeds ShadowJarStyleCheckTest.
            occurrences shouldBe 6
        }

        "Dockerfile exists under src/main/docker/cli/" {
            val dockerfile = File("src/main/docker/cli/Dockerfile")
            dockerfile.exists().shouldBeTrue()
        }

        "Dockerfile uses eclipse-temurin base images" {
            val content = File("src/main/docker/cli/Dockerfile").readText()
            content shouldContain "eclipse-temurin"
        }

        "Dockerfile has ENTRYPOINT referencing kuml-cli.jar" {
            val content = File("src/main/docker/cli/Dockerfile").readText()
            content shouldContain "ENTRYPOINT"
            content shouldContain "kuml-cli.jar"
        }

        "Dockerfile uses multi-stage build (builder stage + runtime stage)" {
            val content = File("src/main/docker/cli/Dockerfile").readText()
            content shouldContain "AS builder"
            content shouldContain "COPY --from=builder"
        }

        "Dockerfile copies the style-worker lib directory alongside the shadow jar" {
            // Regression guard for the Docker/.deb/.rpm style-check gap found in
            // code review of feat/validate-named-arguments — see
            // ShadowJarStyleCheckTest for the end-to-end version of this check.
            val content = File("src/main/docker/cli/Dockerfile").readText()
            content shouldContain "COPY style/ style/"
            content shouldContain "COPY --from=builder /build/style/ style/"
        }

        "Dockerfile carries OCI image labels" {
            val content = File("src/main/docker/cli/Dockerfile").readText()
            content shouldContain "org.opencontainers.image.title"
            content shouldContain "org.opencontainers.image.source"
        }

        "release-installers workflow exists" {
            // Tests run with CWD = kuml-packaging/; walk up to the repo root.
            val workflow =
                File("")
                    .absoluteFile
                    .parentFile // kUML/
                    .resolve(".github/workflows/release-installers.yml")
            workflow.exists().shouldBeTrue()
        }

        "release-installers workflow targets all three platforms" {
            val workflow =
                File("")
                    .absoluteFile
                    .parentFile
                    .resolve(".github/workflows/release-installers.yml")
            val content = workflow.readText()
            content shouldContain "ubuntu-latest"
            content shouldContain "macos-latest"
            content shouldContain "windows-latest"
        }
    })
