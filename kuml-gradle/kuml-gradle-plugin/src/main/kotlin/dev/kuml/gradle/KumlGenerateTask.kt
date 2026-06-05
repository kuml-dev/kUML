package dev.kuml.gradle

import dev.kuml.codegen.api.CodeGenRegistry
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.gradle.internal.GradlePipeline
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Generiert Code aus den UML-Klassendiagrammen in [sourceDir] über den per
 * [generator] gewählten `KumlCodeGenerator` (Auflösung via [CodeGenRegistry],
 * ServiceLoader-discoverable). C4-Skripte werden übersprungen.
 *
 * Output landet in `[outputDir]`. Der konkrete Layout-Stil (z.B. Paket-
 * Verzeichnisse für Java) hängt vom Generator ab.
 */
@CacheableTask
public abstract class KumlGenerateTask
    @Inject
    constructor() : DefaultTask() {
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        public abstract val sourceDir: DirectoryProperty

        @get:OutputDirectory
        public abstract val outputDir: DirectoryProperty

        @get:Input
        public abstract val generator: Property<String>

        @get:Optional
        @get:Input
        public abstract val packageName: Property<String>

        @get:Input
        public abstract val options: MapProperty<String, String>

        init {
            group = "kuml"
            description = "Generiere Code aus *.kuml.kts-Skripten (Kotlin/Java/SQL)."
        }

        @TaskAction
        public fun generate() {
            val src = sourceDir.get().asFile
            val out = outputDir.get().asFile
            val name = generator.get()
            if (CodeGenRegistry.names().isEmpty()) CodeGenRegistry.loadFromClasspath()
            val gen =
                CodeGenRegistry.get(name)
                    ?: throw GradleException(
                        "Unknown codegen plugin '$name'. Registered: ${CodeGenRegistry.names()}",
                    )
            if (!src.exists()) {
                logger.warn("kumlGenerate: sourceDir '{}' does not exist; nothing to generate.", src.path)
                return
            }
            out.mkdirs()

            val opts =
                buildMap<String, String> {
                    packageName.orNull?.takeIf { it.isNotBlank() }?.let { put("package", it) }
                    options.get().forEach { (k, v) -> put(k, v) }
                }

            val scripts = src.walkTopDown().filter { it.isFile && it.name.endsWith(".kuml.kts") }.toList()
            if (scripts.isEmpty()) {
                logger.warn("kumlGenerate: no *.kuml.kts under '{}'.", src.path)
                return
            }

            var totalFiles = 0
            for (script in scripts) {
                val extracted =
                    try {
                        GradlePipeline.evaluate(script)
                    } catch (ex: Exception) {
                        throw GradleException("kumlGenerate: ${ex.message}", ex)
                    }
                if (extracted is ExtractedDiagram.C4) {
                    logger.lifecycle("kumlGenerate: skipping C4 script '${script.name}'")
                    continue
                }
                val diagram = (extracted as ExtractedDiagram.Uml).diagram
                val written = gen.generate(diagram, out, opts)
                totalFiles += written.size
                logger.lifecycle("kumlGenerate: ${script.name} -> ${written.size} file(s)")
            }
            logger.lifecycle("kumlGenerate: wrote $totalFiles file(s) to ${out.path} using '$name'")
        }
    }
