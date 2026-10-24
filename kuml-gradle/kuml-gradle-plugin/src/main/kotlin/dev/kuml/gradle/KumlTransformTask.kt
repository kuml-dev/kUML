package dev.kuml.gradle

import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.GeneratedFileWriter
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.TransformerRegistry
import dev.kuml.core.model.KumlDiagram
import dev.kuml.gradle.internal.MergedModelLoader
import dev.kuml.gradle.internal.ScriptDiscovery
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
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
 * Merges all UML class scripts below [sourceDir] into one model (de-duplicating stub entities, see
 * [dev.kuml.transform.umlerm.UmlModelMerger]) and applies the M2M/M2T transformer [transformer] once.
 *
 * The output directory is cleaned before each run so that no stale files from removed scripts
 * remain. Scripts are processed in sorted relative-path order, so the result is deterministic.
 * Fully compatible with the build cache and the configuration cache.
 */
@CacheableTask
public abstract class KumlTransformTask
    @Inject
    constructor() : DefaultTask() {
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        public abstract val sourceDir: DirectoryProperty

        @get:OutputDirectory
        public abstract val outputDir: DirectoryProperty

        /** Transformer id, for example `uml-to-exposed-via-erm`. Required. */
        @get:Optional
        @get:Input
        public abstract val transformer: Property<String>

        /** Transformer context options, for example `idType=uuid`, `package=com.example.db`. */
        @get:Input
        public abstract val options: MapProperty<String, String>

        /** Name of the merged model. */
        @get:Input
        public abstract val modelName: Property<String>

        /** Upper bound for the number of scripts. */
        @get:Input
        public abstract val maxScripts: Property<Int>

        /** Upper bound for the size of a single script in bytes. */
        @get:Input
        public abstract val maxScriptBytes: Property<Long>

        /** When `true`, scripts that are not UML class diagrams are skipped instead of failing the build. */
        @get:Input
        public abstract val skipNonUmlScripts: Property<Boolean>

        @get:Inject
        protected abstract val fileSystemOperations: FileSystemOperations

        init {
            group = "kuml"
            description = "Merge all *.kuml.kts UML class scripts into one model and apply an M2M/M2T transformer."
            maxScripts.convention(ScriptDiscovery.DEFAULT_MAX_SCRIPTS)
            maxScriptBytes.convention(ScriptDiscovery.DEFAULT_MAX_SCRIPT_BYTES)
            skipNonUmlScripts.convention(false)
        }

        @TaskAction
        public fun transform() {
            val id =
                transformer.orNull?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("kumlTransform: set kuml { transformer.set(\"uml-to-exposed-via-erm\") }")
            val src = sourceDir.get().asFile
            val out = outputDir.get().asFile

            val srcPath = src.canonicalFile.toPath()
            val outPath = out.canonicalFile.toPath()
            if (outPath == srcPath || outPath.startsWith(srcPath) || srcPath.startsWith(outPath)) {
                throw GradleException(
                    "kumlTransform: outputDir must not be, lie inside, or contain sourceDir " +
                        "(the output directory is cleaned before each run).",
                )
            }

            if (TransformerRegistry.ids().isEmpty()) {
                TransformerRegistry.loadFromClasspath(KumlTransformTask::class.java.classLoader)
            }
            val t =
                TransformerRegistry.get<KumlDiagram, List<GeneratedFile>>(id)
                    ?: throw GradleException(
                        "kumlTransform: unknown transformer '$id'. Registered: ${TransformerRegistry.ids()}",
                    )

            val stale = out.listFiles()
            if (stale != null && stale.isNotEmpty()) {
                fileSystemOperations.delete { spec -> spec.delete(*stale) }
            }
            out.mkdirs()

            val scripts =
                ScriptDiscovery.discover(
                    sourceDir = src,
                    maxScripts = maxScripts.get(),
                    maxScriptBytes = maxScriptBytes.get(),
                )
            if (scripts.isEmpty()) {
                logger.warn("kumlTransform: no *.kuml.kts under '{}'; nothing to transform.", src.path)
                return
            }
            val merged =
                MergedModelLoader.load(
                    scripts = scripts,
                    skipNonUml = skipNonUmlScripts.get(),
                    mergedName = modelName.get(),
                    onSkip = { logger.lifecycle("kumlTransform: skipping non-UML-class script '{}'", it) },
                )
            if (merged == null) {
                logger.warn("kumlTransform: no UML class scripts to transform.")
                return
            }

            val result =
                try {
                    t.transform(source = merged.diagram, ctx = TransformContext(options = options.get()))
                } catch (ex: ClassCastException) {
                    throw GradleException(
                        "kumlTransform: transformer '$id' does not accept a merged UML class diagram as input " +
                            "(${ex.message}); choose a transformer that consumes UML class diagrams, " +
                            "for example 'uml-to-exposed-via-erm'.",
                        ex,
                    )
                }
            when (result) {
                is TransformResult.Success -> {
                    val rawOutput: Any? = result.output
                    val files =
                        try {
                            GeneratedFileWriter.requireFiles(output = rawOutput, transformerId = id)
                        } catch (ex: IllegalArgumentException) {
                            throw GradleException("kumlTransform: ${ex.message}", ex)
                        }
                    try {
                        GeneratedFileWriter.write(files = files, outputDir = out, rejectDuplicates = true)
                    } catch (ex: IllegalArgumentException) {
                        throw GradleException("kumlTransform: ${ex.message}", ex)
                    }
                    logger.lifecycle(
                        "kumlTransform: merged {} script(s) -> {} file(s)",
                        merged.scriptCount,
                        files.size,
                    )
                }
                is TransformResult.Failure ->
                    throw GradleException(
                        "kumlTransform: transformer '$id' failed: " + result.errors.joinToString("; ") { it.message },
                    )
            }
        }
    }
