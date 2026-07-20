package dev.kuml.gradle

import dev.kuml.gradle.internal.GradlePipeline
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Rendert alle `*.kuml.kts`-Skripte unterhalb von [sourceDir] zu SVG bzw. PNG.
 *
 * Output: `<outputDir>/<relative path of script>.svg|png` (Ordnerstruktur des
 * Source-Ordners wird gespiegelt). Inkrementelle Builds: Gradle prüft mtime
 * der Inputs gegen die Outputs und überspringt unveränderte Dateien.
 */
@CacheableTask
public abstract class KumlRenderTask
    @Inject
    constructor() : DefaultTask() {
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        public abstract val sourceDir: DirectoryProperty

        @get:OutputDirectory
        public abstract val outputDir: DirectoryProperty

        @get:Input
        public abstract val format: Property<String>

        @get:Input
        public abstract val theme: Property<String>

        @get:Input
        public abstract val widthPx: Property<Int>

        // "Powered by kUML" branding — opt-in visible watermark. Declared
        // `@Input` (not just read at execution time) so a second run after
        // flipping this property re-renders instead of staying UP-TO-DATE
        // with the stale (no-watermark) output.
        @get:Input
        public abstract val watermark: Property<Boolean>

        init {
            group = "kuml"
            description = "Rendere alle *.kuml.kts-Skripte zu SVG/PNG."
        }

        @TaskAction
        public fun render() {
            val src = sourceDir.get().asFile
            val out = outputDir.get().asFile
            val fmt = format.get().lowercase()
            require(fmt == "svg" || fmt == "png") { "Unknown format '$fmt'. Allowed: svg, png." }

            if (!src.exists()) {
                logger.warn("kumlRender: sourceDir '{}' does not exist; nothing to render.", src.path)
                return
            }

            val theme = GradlePipeline.resolveTheme(theme.get())
            val scripts =
                src.walkTopDown().filter { it.isFile && it.name.endsWith(".kuml.kts") }.toList()
            if (scripts.isEmpty()) {
                logger.warn("kumlRender: no *.kuml.kts under '{}'.", src.path)
                return
            }

            var rendered = 0
            for (script in scripts) {
                val rel = script.relativeTo(src).path.removeSuffix(".kuml.kts")
                val outFile = out.resolve("$rel.$fmt")
                outFile.parentFile.mkdirs()
                val extracted =
                    try {
                        GradlePipeline.evaluate(script)
                    } catch (ex: Exception) {
                        throw GradleException("kumlRender: ${ex.message}", ex)
                    }
                when (fmt) {
                    "svg" -> outFile.writeText(GradlePipeline.renderSvg(extracted, theme, watermark.get()))
                    "png" -> outFile.writeBytes(GradlePipeline.renderPng(extracted, theme, widthPx.get(), watermark.get()))
                }
                rendered++
                logger.lifecycle("kumlRender: ${script.name} -> ${outFile.path}")
            }
            logger.lifecycle("kumlRender: rendered $rendered diagram(s) to ${out.path}")
        }
    }
