package dev.kuml.gradle

import dev.kuml.core.ocl.KumlValidationResult
import dev.kuml.core.ocl.OclValidator
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.gradle.internal.GradlePipeline
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Validiert alle `*.kuml.kts`-Skripte in [sourceDir] gegen ihre OCL-Invarianten.
 *
 * Verwendet [OclValidator] aus `kuml-core-ocl`. C4-Skripte werden übersprungen
 * (kein OCL für C4-Diagramme). Bei Violations wird – sofern [failOnViolations]
 * gesetzt – der Build mit einer [GradleException] abgebrochen; ansonsten landet
 * nur eine Warnung im Log.
 *
 * Diese Task hat keinen `@OutputDirectory`, sondern ist als reine Check-Task
 * konzipiert; entsprechend taucht sie sinnvoll in der `check`-Lifecycle auf.
 */
@CacheableTask
public abstract class KumlValidateTask
    @Inject
    constructor() : DefaultTask() {
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        public abstract val sourceDir: DirectoryProperty

        @get:Input
        public abstract val failOnViolations: Property<Boolean>

        init {
            group = "verification"
            description = "Validiere *.kuml.kts-Skripte gegen ihre OCL-Invarianten."
        }

        @TaskAction
        public fun validate() {
            val src = sourceDir.get().asFile
            if (!src.exists()) {
                logger.warn("kumlValidate: sourceDir '{}' does not exist; nothing to validate.", src.path)
                return
            }

            val scripts =
                src.walkTopDown().filter { it.isFile && it.name.endsWith(".kuml.kts") }.toList()
            if (scripts.isEmpty()) {
                logger.warn("kumlValidate: no *.kuml.kts under '{}'.", src.path)
                return
            }

            var totalChecked = 0
            var totalViolations = 0
            val failures = mutableListOf<String>()

            for (script in scripts) {
                val extracted =
                    try {
                        GradlePipeline.evaluate(script)
                    } catch (ex: Exception) {
                        throw GradleException("kumlValidate: ${ex.message}", ex)
                    }
                if (extracted is ExtractedDiagram.C4) {
                    logger.lifecycle("kumlValidate: skipping C4 script '${script.name}'")
                    continue
                }
                if (extracted is ExtractedDiagram.Sysml2) {
                    // V2.0.4+ — OCL constraints are UML-scoped; SysML 2 has its own
                    // future validation surface. Skip silently, mirror the C4 path.
                    logger.lifecycle("kumlValidate: skipping SysML 2 script '${script.name}'")
                    continue
                }
                val diagram = (extracted as ExtractedDiagram.Uml).diagram
                val result: KumlValidationResult = OclValidator.validate(diagram)
                totalChecked++
                if (!result.valid) {
                    totalViolations += result.violations.size
                    for (v in result.violations) {
                        val line = "  - [${script.name}] ${v.classifierName} :: ${v.constraintName}: ${v.message}"
                        failures += line
                        logger.error(line)
                    }
                } else {
                    logger.lifecycle("kumlValidate: ${script.name} -> OK")
                }
            }

            if (totalViolations == 0) {
                logger.lifecycle("kumlValidate: checked $totalChecked diagram(s); no violations.")
                return
            }

            val summary =
                "kumlValidate: $totalViolations violation(s) in $totalChecked diagram(s):\n" +
                    failures.joinToString("\n")
            if (failOnViolations.get()) {
                throw GradleException(summary)
            } else {
                logger.warn(summary)
            }
        }
    }
