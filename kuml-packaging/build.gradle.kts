// ─────────────────────────────────────────────────────────────────────────────
// :kuml-packaging — V2.0.32 Distribution Phase 1
//
// Produces platform-native installers via jpackage and a slim Docker CLI image.
//
// Tasks (group = "distribution"):
//   packageDeb     — Debian .deb  (Linux only, OS-gated)
//   packageRpm     — RPM .rpm     (Linux only, OS-gated)
//   packageDmg     — macOS DMG    (macOS only, OS-gated, unsigned — Phase 2 adds signing)
//   packageMsi     — Windows MSI  (Windows only, OS-gated, unsigned — Phase 2 adds signing)
//   dockerBuildCli — Docker image for ghcr.io/kuml-dev/kuml-cli
//
// All five tasks depend on :kuml-cli:shadowJar; none are included in `check`.
// Signing is deferred to Phase 2.
// ─────────────────────────────────────────────────────────────────────────────

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// ─────────────────────────────────────────────────────────────────────────────
// Version helpers — resolved at configuration time from the root project.
// ─────────────────────────────────────────────────────────────────────────────
val kumlVersion: String = project.version.toString()

// macOS jpackage (DMG/PKG) requires CFBundleShortVersionString to start with a
// component ≥ 1.  For pre-1.0 semantic versions ("0.MINOR.PATCH") we drop the
// leading "0." and promote minor to the first component, giving jpackage a
// valid three-part version while the displayed app name still shows "kUML 0.x.y".
// Example: "0.6.0" → "6.0.0".
val macOsPackageVersion: String =
    run {
        if (kumlVersion.startsWith("0.")) {
            val rest = kumlVersion.removePrefix("0.").split(".")
            "${rest[0]}.${rest.getOrElse(1) { "0" }}.0"
        } else {
            kumlVersion
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Shadow JAR location — computed from project layout conventions.
// Captured as plain Strings so doFirst(object : Action<Task>) closures stay
// Gradle Configuration Cache compatible (no Gradle script object references).
// ─────────────────────────────────────────────────────────────────────────────
val shadowJarPath: String =
    project(":kuml-cli")
        .layout.buildDirectory
        .file("libs/kuml-cli-$kumlVersion-all.jar")
        .get()
        .asFile.absolutePath

val distDirPath: String =
    layout.buildDirectory
        .dir("dist")
        .get()
        .asFile.absolutePath

// ─────────────────────────────────────────────────────────────────────────────
// jpackage — DEB (Linux)
// ─────────────────────────────────────────────────────────────────────────────
val packageDeb =
    tasks.register<Exec>("packageDeb") {
        group = "distribution"
        description = "Build Debian (.deb) package via jpackage (Linux only)"
        onlyIf {
            org.gradle.internal.os.OperatingSystem
                .current()
                .isLinux
        }
        dependsOn(":kuml-cli:shadowJar")
        // Capture Strings only — no Gradle script object references — for CC safety.
        val jarP = shadowJarPath
        val destP = distDirPath
        val ver = kumlVersion
        doFirst(
            object : Action<Task> {
                override fun execute(task: Task) {
                    val jar = File(jarP)
                    File(destP).mkdirs()
                    commandLine(
                        "jpackage",
                        "--input",
                        jar.parent,
                        "--main-jar",
                        jar.name,
                        "--name",
                        "kuml",
                        "--app-version",
                        ver,
                        "--type",
                        "deb",
                        "--dest",
                        destP,
                        "--description",
                        "Kotlin-native UML and SysML 2 modelling DSL",
                        "--vendor",
                        "kuml.dev",
                        "--linux-package-name",
                        "kuml",
                    )
                }
            },
        )
    }

// ─────────────────────────────────────────────────────────────────────────────
// jpackage — RPM (Linux)
// ─────────────────────────────────────────────────────────────────────────────
val packageRpm =
    tasks.register<Exec>("packageRpm") {
        group = "distribution"
        description = "Build RPM package via jpackage (Linux only)"
        onlyIf {
            org.gradle.internal.os.OperatingSystem
                .current()
                .isLinux
        }
        dependsOn(":kuml-cli:shadowJar")
        val jarP = shadowJarPath
        val destP = distDirPath
        val ver = kumlVersion
        doFirst(
            object : Action<Task> {
                override fun execute(task: Task) {
                    val jar = File(jarP)
                    File(destP).mkdirs()
                    commandLine(
                        "jpackage",
                        "--input",
                        jar.parent,
                        "--main-jar",
                        jar.name,
                        "--name",
                        "kuml",
                        "--app-version",
                        ver,
                        "--type",
                        "rpm",
                        "--dest",
                        destP,
                        "--description",
                        "Kotlin-native UML and SysML 2 modelling DSL",
                        "--vendor",
                        "kuml.dev",
                        "--linux-package-name",
                        "kuml",
                    )
                }
            },
        )
    }

// ─────────────────────────────────────────────────────────────────────────────
// jpackage — DMG (macOS, unsigned — Phase 2 adds signing)
//
// --app-version uses macOsPackageVersion (e.g. "6.0.0" for kUML 0.6.0) because
// macOS CFBundleShortVersionString requires the first component to be ≥ 1.
// ─────────────────────────────────────────────────────────────────────────────
val packageDmg =
    tasks.register<Exec>("packageDmg") {
        group = "distribution"
        description = "Build macOS DMG (unsigned) via jpackage (macOS only)"
        onlyIf {
            org.gradle.internal.os.OperatingSystem
                .current()
                .isMacOsX
        }
        dependsOn(":kuml-cli:shadowJar")
        val jarP = shadowJarPath
        val destP = distDirPath
        val macVer = macOsPackageVersion
        doFirst(
            object : Action<Task> {
                override fun execute(task: Task) {
                    val jar = File(jarP)
                    File(destP).mkdirs()
                    commandLine(
                        "jpackage",
                        "--input",
                        jar.parent,
                        "--main-jar",
                        jar.name,
                        "--name",
                        "kUML",
                        "--app-version",
                        macVer,
                        "--type",
                        "dmg",
                        "--dest",
                        destP,
                        "--mac-package-name",
                        "kUML",
                        // --mac-sign is intentionally omitted — Phase 2 adds Apple Developer signing
                    )
                }
            },
        )
    }

// ─────────────────────────────────────────────────────────────────────────────
// jpackage — MSI (Windows, unsigned — Phase 2 adds signing)
// ─────────────────────────────────────────────────────────────────────────────
val packageMsi =
    tasks.register<Exec>("packageMsi") {
        group = "distribution"
        description = "Build Windows MSI (unsigned) via jpackage (Windows only)"
        onlyIf {
            org.gradle.internal.os.OperatingSystem
                .current()
                .isWindows
        }
        dependsOn(":kuml-cli:shadowJar")
        val jarP = shadowJarPath
        val destP = distDirPath
        val ver = kumlVersion
        doFirst(
            object : Action<Task> {
                override fun execute(task: Task) {
                    val jar = File(jarP)
                    File(destP).mkdirs()
                    commandLine(
                        "jpackage",
                        "--input",
                        jar.parent,
                        "--main-jar",
                        jar.name,
                        "--name",
                        "kUML",
                        "--app-version",
                        ver,
                        "--type",
                        "msi",
                        "--dest",
                        destP,
                        "--description",
                        "Kotlin-native UML and SysML 2 modelling DSL",
                        "--vendor",
                        "kuml.dev",
                    )
                }
            },
        )
    }

// ─────────────────────────────────────────────────────────────────────────────
// Docker — build slim CLI image and tag as ghcr.io/kuml-dev/kuml-cli:<version>
// isIgnoreExitValue = true so builds without Docker installed don't break CI.
// ─────────────────────────────────────────────────────────────────────────────
val dockerBuildCli =
    tasks.register<Exec>("dockerBuildCli") {
        group = "distribution"
        description = "Build Docker CLI image and tag as ghcr.io/kuml-dev/kuml-cli:<version>"
        dependsOn(":kuml-cli:shadowJar")
        isIgnoreExitValue = true
        val jarP = shadowJarPath
        val dockerFilePath =
            layout.projectDirectory
                .file("src/main/docker/cli/Dockerfile")
                .asFile.absolutePath
        val ver = kumlVersion
        doFirst(
            object : Action<Task> {
                override fun execute(task: Task) {
                    val jar = File(jarP)
                    val tag = "ghcr.io/kuml-dev/kuml-cli:$ver"
                    commandLine(
                        "docker",
                        "build",
                        "--file",
                        dockerFilePath,
                        "--build-arg",
                        "JAR_FILE=${jar.name}",
                        "--tag",
                        tag,
                        "--tag",
                        "ghcr.io/kuml-dev/kuml-cli:latest",
                        jar.parent,
                    )
                }
            },
        )
    }
