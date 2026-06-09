package dev.kuml.packaging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * V2.0.32 — packaging smoke tests.
 *
 * These tests verify the static artefacts (Dockerfile, build script) are
 * correctly structured. Actual jpackage / Docker invocations are OS-gated and
 * run only in CI via the release-installers.yml workflow — they are not part
 * of the local `check` task.
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
