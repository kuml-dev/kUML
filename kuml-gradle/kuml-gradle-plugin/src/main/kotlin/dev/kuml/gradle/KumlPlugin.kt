package dev.kuml.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Einstiegspunkt für das `dev.kuml`-Gradle-Plugin.
 *
 * ### Anwendung
 *
 * ```kotlin
 * plugins {
 *     id("dev.kuml") version "<version>"
 * }
 *
 * kuml {
 *     sourceDir.set(file("src/main/kuml"))
 *     outputDir.set(layout.buildDirectory.dir("kuml"))
 *     format.set("svg")
 *     theme.set("kuml")
 *     generator.set("kotlin")
 *     generatePackage.set("com.example.domain")
 * }
 * ```
 *
 * ### Registrierte Tasks
 *
 * | Task            | Gruppe        | Zweck                                             |
 * |-----------------|---------------|---------------------------------------------------|
 * | `kumlRender`    | `kuml`        | Rendert `*.kuml.kts` zu SVG/PNG.                  |
 * | `kumlGenerate`  | `kuml`        | Generiert Code via `CodeGenRegistry`.             |
 * | `kumlValidate`  | `verification`| Prüft OCL-Invarianten; hängt an `check`.          |
 *
 * Defaults werden über die [KumlExtension] als Conventions an die Tasks
 * verdrahtet — Anwender können einzelne Tasks aber jederzeit überschreiben
 * (`tasks.named<KumlRenderTask>("kumlRender") { … }`).
 */
public class KumlPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("kuml", KumlExtension::class.java)

        // Sensible Defaults: src/main/kuml als Quelle, build/kuml als Ziel.
        ext.sourceDir.convention(project.layout.projectDirectory.dir("src/main/kuml"))
        ext.outputDir.convention(project.layout.buildDirectory.dir("kuml"))

        project.tasks.register("kumlRender", KumlRenderTask::class.java) { t ->
            t.sourceDir.convention(ext.sourceDir)
            t.outputDir.convention(ext.outputDir)
            t.format.convention(ext.format)
            t.theme.convention(ext.theme)
            t.widthPx.convention(ext.widthPx)
            t.watermark.convention(ext.watermark)
        }

        project.tasks.register("kumlGenerate", KumlGenerateTask::class.java) { t ->
            t.sourceDir.convention(ext.sourceDir)
            t.outputDir.convention(ext.outputDir.map { it.dir("generated") })
            t.generator.convention(ext.generator)
            t.packageName.convention(ext.generatePackage)
            t.options.convention(ext.generateOptions)
        }

        val validate =
            project.tasks.register("kumlValidate", KumlValidateTask::class.java) { t ->
                t.sourceDir.convention(ext.sourceDir)
                t.failOnViolations.convention(ext.failOnValidationViolations)
            }

        // Hänge `kumlValidate` an die `check`-Lifecycle, falls vorhanden
        // (java/base-Plugin angewandt). Sonst ist der User explizit.
        project.tasks.matching { it.name == "check" }.configureEach { check ->
            check.dependsOn(validate)
        }
    }
}
