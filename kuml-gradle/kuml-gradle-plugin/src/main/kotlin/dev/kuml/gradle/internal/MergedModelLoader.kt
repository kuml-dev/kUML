package dev.kuml.gradle.internal

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.transform.umlerm.UmlModelMergeException
import dev.kuml.transform.umlerm.UmlModelMerger
import dev.kuml.transform.umlerm.UmlModelSource
import org.gradle.api.GradleException

/** The result of merging all UML class scripts of a source directory. */
internal data class MergedModel(
    val diagram: KumlDiagram,
    val scriptCount: Int,
)

/** Evaluates scripts one by one and merges their UML class diagrams into one model. */
internal object MergedModelLoader {
    /**
     * @param skipNonUml when `true`, scripts that are not UML class diagrams are skipped (reported
     *   through [onSkip]); when `false` they fail the build naming the script.
     * @return the merged model, or `null` if no UML class script remained.
     */
    fun load(
        scripts: List<DiscoveredScript>,
        skipNonUml: Boolean,
        mergedName: String,
        onSkip: (String) -> Unit,
    ): MergedModel? {
        val sources = mutableListOf<UmlModelSource>()
        for (script in scripts) {
            val extracted =
                try {
                    GradlePipeline.evaluate(script.file)
                } catch (ex: Exception) {
                    throw GradleException("Script '${script.relativePath}' failed: ${ex.message}", ex)
                }
            val diagram = (extracted as? ExtractedDiagram.Uml)?.diagram?.takeIf { it.type == DiagramType.CLASS }
            if (diagram == null) {
                if (skipNonUml) {
                    onSkip(script.relativePath)
                    continue
                }
                throw GradleException(
                    "Script '${script.relativePath}' is not a UML class diagram; " +
                        "multi-script mode merges UML class diagrams only (set skipNonUmlScripts to skip others).",
                )
            }
            sources += UmlModelSource(origin = script.relativePath, diagram = diagram)
        }
        if (sources.isEmpty()) return null
        val merged =
            try {
                UmlModelMerger.merge(sources = sources, mergedName = mergedName)
            } catch (ex: UmlModelMergeException) {
                throw GradleException(ex.message.orEmpty(), ex)
            }
        return MergedModel(diagram = merged, scriptCount = sources.size)
    }
}
