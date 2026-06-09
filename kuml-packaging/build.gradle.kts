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
// Version helper — resolved at configuration time from the root project.
// ─────────────────────────────────────────────────────────────────────────────
val kumlVersion: String = project.version.toString()

// ─────────────────────────────────────────────────────────────────────────────
// Shared helper: locate the shadow JAR produced by :kuml-cli:shadowJar.
// We resolve the task as a plain Jar (the base type of ShadowJar) so this
// module does not need to apply the shadow plugin itself.
// ─────────────────────────────────────────────────────────────────────────────
fun shadowJarFile(): Provider<File> =
    project(":kuml-cli")
        .tasks
        .named("shadowJar", Jar::class.java)
        .map { it.archiveFile.get().asFile }

// ─────────────────────────────────────────────────────────────────────────────
// jpackage — DEB (Linux)
// ─────────────────────────────────────────────────────────────────────────────
val packageDeb by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build Debian (.deb) package via jpackage (Linux only)"
    onlyIf {
        org.gradle.internal.os.OperatingSystem
            .current()
            .isLinux
    }
    dependsOn(":kuml-cli:shadowJar")
    doFirst {
        val jarFile = shadowJarFile().get()
        val dest =
            layout.buildDirectory
                .dir("dist")
                .get()
                .asFile
        dest.mkdirs()
        commandLine(
            "jpackage",
            "--input",
            jarFile.parent,
            "--main-jar",
            jarFile.name,
            "--name",
            "kuml",
            "--app-version",
            kumlVersion,
            "--type",
            "deb",
            "--dest",
            dest.absolutePath,
            "--description",
            "Kotlin-native UML and SysML 2 modelling DSL",
            "--vendor",
            "kuml.dev",
            "--linux-package-name",
            "kuml",
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// jpackage — RPM (Linux)
// ─────────────────────────────────────────────────────────────────────────────
val packageRpm by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build RPM package via jpackage (Linux only)"
    onlyIf {
        org.gradle.internal.os.OperatingSystem
            .current()
            .isLinux
    }
    dependsOn(":kuml-cli:shadowJar")
    doFirst {
        val jarFile = shadowJarFile().get()
        val dest =
            layout.buildDirectory
                .dir("dist")
                .get()
                .asFile
        dest.mkdirs()
        commandLine(
            "jpackage",
            "--input",
            jarFile.parent,
            "--main-jar",
            jarFile.name,
            "--name",
            "kuml",
            "--app-version",
            kumlVersion,
            "--type",
            "rpm",
            "--dest",
            dest.absolutePath,
            "--description",
            "Kotlin-native UML and SysML 2 modelling DSL",
            "--vendor",
            "kuml.dev",
            "--linux-package-name",
            "kuml",
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// jpackage — DMG (macOS, unsigned — Phase 2 adds signing)
// ─────────────────────────────────────────────────────────────────────────────
val packageDmg by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build macOS DMG (unsigned) via jpackage (macOS only)"
    onlyIf {
        org.gradle.internal.os.OperatingSystem
            .current()
            .isMacOsX
    }
    dependsOn(":kuml-cli:shadowJar")
    doFirst {
        val jarFile = shadowJarFile().get()
        val dest =
            layout.buildDirectory
                .dir("dist")
                .get()
                .asFile
        dest.mkdirs()
        commandLine(
            "jpackage",
            "--input",
            jarFile.parent,
            "--main-jar",
            jarFile.name,
            "--name",
            "kUML",
            "--app-version",
            kumlVersion,
            "--type",
            "dmg",
            "--dest",
            dest.absolutePath,
            "--mac-package-name",
            "kUML",
            // --mac-sign is intentionally omitted — Phase 2 adds Apple Developer signing
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// jpackage — MSI (Windows, unsigned — Phase 2 adds signing)
// ─────────────────────────────────────────────────────────────────────────────
val packageMsi by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build Windows MSI (unsigned) via jpackage (Windows only)"
    onlyIf {
        org.gradle.internal.os.OperatingSystem
            .current()
            .isWindows
    }
    dependsOn(":kuml-cli:shadowJar")
    doFirst {
        val jarFile = shadowJarFile().get()
        val dest =
            layout.buildDirectory
                .dir("dist")
                .get()
                .asFile
        dest.mkdirs()
        commandLine(
            "jpackage",
            "--input",
            jarFile.parent,
            "--main-jar",
            jarFile.name,
            "--name",
            "kUML",
            "--app-version",
            kumlVersion,
            "--type",
            "msi",
            "--dest",
            dest.absolutePath,
            "--description",
            "Kotlin-native UML and SysML 2 modelling DSL",
            "--vendor",
            "kuml.dev",
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Docker — build slim CLI image and tag as ghcr.io/kuml-dev/kuml-cli:<version>
// isIgnoreExitValue = true so builds without Docker installed don't break CI.
// ─────────────────────────────────────────────────────────────────────────────
val dockerBuildCli by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build Docker CLI image and tag as ghcr.io/kuml-dev/kuml-cli:<version>"
    dependsOn(":kuml-cli:shadowJar")
    isIgnoreExitValue = true
    doFirst {
        val jarFile = shadowJarFile().get()
        val dockerFile = file("src/main/docker/cli/Dockerfile")
        val tag = "ghcr.io/kuml-dev/kuml-cli:$kumlVersion"
        commandLine(
            "docker",
            "build",
            "--file",
            dockerFile.absolutePath,
            "--build-arg",
            "JAR_FILE=${jarFile.name}",
            "--tag",
            tag,
            "--tag",
            "ghcr.io/kuml-dev/kuml-cli:latest",
            jarFile.parent,
        )
    }
}
