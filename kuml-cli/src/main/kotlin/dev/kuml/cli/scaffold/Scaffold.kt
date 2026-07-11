package dev.kuml.cli.scaffold

import java.io.File

/**
 * Shared scaffold engine used by both `kuml plugin init` and `kuml workspace init`.
 *
 * Extracted in V3.6.2 (FT-4) from `dev.kuml.cli.plugin.PluginInitCommand`, where
 * [TemplateEngine] and [TemplateFile] originally lived. Behavior is unchanged —
 * this is a mechanical move plus a generalisation of the write loop into
 * [Scaffolder] so a second command (`workspace init`) can reuse the exact same
 * mechanics (force-guard, classpath resource loading, `{{var}}` rendering of both
 * content and output path, directory creation) without duplicating them.
 */
internal object TemplateEngine {
    private val TOKEN_REGEX = Regex("""\{\{([^}]+)}}""")

    /**
     * Replaces `{{var}}` tokens with values from [vars].
     * Throws [IllegalArgumentException] for unknown tokens.
     */
    fun render(
        template: String,
        vars: Map<String, String>,
    ): String =
        TOKEN_REGEX.replace(template) { match ->
            val key = match.groupValues[1]
            vars[key] ?: throw IllegalArgumentException(
                "Unknown template variable '{{$key}}' — known: ${vars.keys.sorted()}",
            )
        }
}

/**
 * Maps a classpath resource path to an output path (both may contain `{{vars}}`).
 *
 * @param resourcePath Classpath path under the command's template base (e.g.
 *  `plugin-templates/` or `workspace-templates/`), e.g. `theme/build.gradle.kts.tmpl`
 * @param outputPath   Path relative to the target dir, e.g. `build.gradle.kts`; may contain `{{var}}`
 */
internal data class TemplateFile(
    val resourcePath: String,
    val outputPath: String,
)

/**
 * Generic scaffold write loop, reused by [dev.kuml.cli.plugin.PluginScaffolder] and
 * `dev.kuml.cli.workspace.WorkspaceScaffolder`.
 *
 * Mechanics (byte-for-byte identical to the original `PluginScaffolder.scaffold`):
 * force-guard against a non-empty [targetDir], load each template from the classpath,
 * render `{{var}}` tokens in both content and output path via [TemplateEngine], create
 * parent directories, write the file, and report it via [echo].
 */
internal object Scaffolder {
    /**
     * Renders and writes all [templates] for [vars] into [targetDir].
     *
     * @param force When `false`, aborts if [targetDir] already exists and is non-empty.
     */
    fun scaffold(
        templates: List<TemplateFile>,
        vars: Map<String, String>,
        targetDir: File,
        force: Boolean,
        echo: (String) -> Unit,
    ) {
        if (targetDir.exists() && targetDir.isDirectory && targetDir.list()?.isNotEmpty() == true && !force) {
            throw IllegalStateException(
                "Target directory '${targetDir.absolutePath}' already exists and is non-empty. " +
                    "Use --force to overwrite.",
            )
        }
        for (tmpl in templates) {
            val content = loadResource(tmpl.resourcePath)
            val rendered = TemplateEngine.render(content, vars)
            val outRelative = TemplateEngine.render(tmpl.outputPath, vars)
            val outFile = File(targetDir, outRelative)
            outFile.parentFile?.mkdirs()
            outFile.writeText(rendered)
            echo("  created: $outRelative")
        }
    }

    private fun loadResource(path: String): String {
        val stream =
            Scaffolder::class.java.classLoader.getResourceAsStream(path)
                ?: throw IllegalStateException(
                    "Missing classpath resource: $path — this is a bug in kuml-cli",
                )
        return stream.use { it.bufferedReader().readText() }
    }
}
