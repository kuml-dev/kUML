package dev.kuml.cli.plugin

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.mordant.terminal.prompt
import dev.kuml.cli.ExitCodes
import dev.kuml.cli.KumlVersion
import java.io.File

// ── Constants ─────────────────────────────────────────────────────────────────

private const val KUML_VERSION_RANGE = ">=0.12.0"

private val VALID_CATEGORIES = setOf("theme", "renderer", "layout", "codegen", "reverse")

// ── Data model ────────────────────────────────────────────────────────────────

/**
 * Resolved specification for a plugin project scaffold.
 *
 * All derived fields are computed by [PluginInitSpec.Companion.from].
 */
internal data class PluginInitSpec(
    val category: String,
    val pluginId: String,
    val pluginName: String,
    val artifactId: String,
    val packageName: String,
    val packageDir: String,
    val className: String,
    val groupId: String,
    val version: String = "1.0.0",
    val maintainer: String,
    val homepage: String,
    val licenseSpdx: String,
    val kumlVersion: String = KumlVersion.version,
    val kumlVersionRange: String = KUML_VERSION_RANGE,
) {
    fun toVars(): Map<String, String> =
        mapOf(
            "pluginId" to pluginId,
            "pluginName" to pluginName,
            "artifactId" to artifactId,
            "packageName" to packageName,
            "packageDir" to packageDir,
            "className" to className,
            "groupId" to groupId,
            "version" to version,
            "maintainer" to maintainer,
            "homepage" to homepage,
            "licenseSpdx" to licenseSpdx,
            "kumlVersion" to kumlVersion,
            "kumlVersionRange" to kumlVersionRange,
            "category" to category,
        )

    companion object {
        fun from(
            category: String,
            pluginId: String,
            pluginName: String,
            maintainer: String,
            homepage: String,
            licenseSpdx: String,
        ): PluginInitSpec {
            val artifactId = deriveArtifactId(pluginId)
            val packageName = derivePackageName(pluginId)
            val packageDir = packageName.replace('.', '/')
            val className = deriveClassName(artifactId, category)
            val groupId = deriveGroupId(pluginId)
            return PluginInitSpec(
                category = category,
                pluginId = pluginId,
                pluginName = pluginName,
                artifactId = artifactId,
                packageName = packageName,
                packageDir = packageDir,
                className = className,
                groupId = groupId,
                maintainer = maintainer,
                homepage = homepage,
                licenseSpdx = licenseSpdx,
            )
        }

        /**
         * Last dot-separated segment, lowercased, non-`[a-z0-9-]` replaced by `-`.
         * E.g. `com.example.my-theme` → `my-theme`.
         */
        fun deriveArtifactId(pluginId: String): String =
            pluginId
                .split('.')
                .last()
                .lowercase()
                .replace(Regex("[^a-z0-9-]"), "-")
                .trim('-')

        /**
         * Derives a valid Kotlin package name from the plugin id.
         * Dashes are removed from each segment; pure-segment IDs get a `dev.kuml.plugin.` prefix.
         */
        fun derivePackageName(pluginId: String): String {
            val segments = pluginId.split('.')
            return if (segments.size < 2) {
                "dev.kuml.plugin.${pluginId.replace(Regex("[^a-z0-9]"), "")}"
            } else {
                segments.joinToString(".") { seg ->
                    seg.replace(Regex("[^a-z0-9]"), "").ifEmpty { "plugin" }
                }
            }
        }

        /**
         * Converts `my-theme` + `theme` → `MyThemePlugin`.
         * PascalCase from the artifact id, with the category and `Plugin` appended unless
         * the pascal form already ends with the category name (e.g. `my-theme` → `MyTheme`
         * already ends with `Theme`, so we append only `Plugin`).
         */
        fun deriveClassName(
            artifactId: String,
            category: String,
        ): String {
            val pascal =
                artifactId
                    .split('-')
                    .joinToString("") { seg -> seg.replaceFirstChar { it.uppercaseChar() } }
            val categoryPascal = category.replaceFirstChar { it.uppercaseChar() }
            return when {
                pascal.endsWith("${categoryPascal}Plugin", ignoreCase = true) -> pascal
                pascal.endsWith(categoryPascal, ignoreCase = true) -> "${pascal}Plugin"
                else -> "$pascal${categoryPascal}Plugin"
            }
        }

        /**
         * Derives the Maven group coordinate from the plugin id.
         *
         * All dot-separated segments except the last, lowercased, with dashes stripped from
         * each segment. For a single-segment plugin id (no dot), returns `dev.kuml.plugin`.
         *
         * Examples:
         * - `com.example.my-theme` → `com.example`
         * - `org.acme.subgroup.myplugin` → `org.acme.subgroup`
         * - `mytheme` → `dev.kuml.plugin`
         */
        fun deriveGroupId(pluginId: String): String {
            val segments = pluginId.split('.')
            return if (segments.size < 2) {
                "dev.kuml.plugin"
            } else {
                segments
                    .dropLast(1)
                    .joinToString(".") { seg -> seg.replace(Regex("[^a-z0-9]"), "").ifEmpty { "plugin" } }
            }
        }
    }
}

// ── Template engine ───────────────────────────────────────────────────────────

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

// ── Template file descriptor ──────────────────────────────────────────────────

/**
 * Maps a classpath resource path to an output path (both may contain `{{vars}}`).
 *
 * @param resourcePath Classpath path under `plugin-templates/`, e.g. `theme/build.gradle.kts.tmpl`
 * @param outputPath   Path relative to the target dir, e.g. `build.gradle.kts`; may contain `{{var}}`
 */
internal data class TemplateFile(
    val resourcePath: String,
    val outputPath: String,
)

// ── Scaffolder ────────────────────────────────────────────────────────────────

internal object PluginScaffolder {
    private const val RESOURCE_BASE = "plugin-templates"

    /** Ordered list of template files for each category. GraalVM-safe — no classpath directory scan. */
    fun templateFiles(category: String): List<TemplateFile> {
        val shared = "$RESOURCE_BASE/_shared"
        val cat = "$RESOURCE_BASE/$category"
        return listOf(
            TemplateFile("$shared/settings.gradle.kts.tmpl", "settings.gradle.kts"),
            TemplateFile("$shared/gitignore.tmpl", ".gitignore"),
            TemplateFile("$cat/build.gradle.kts.tmpl", "build.gradle.kts"),
            TemplateFile("$cat/kuml-plugin.json.tmpl", "src/main/resources/kuml-plugin.json"),
            TemplateFile("$cat/README.adoc.tmpl", "README.adoc"),
            TemplateFile(
                "$cat/Placeholder.kt.tmpl",
                "src/main/kotlin/{{packageDir}}/{{className}}.kt",
            ),
        )
    }

    /**
     * Renders and writes all template files for the given category and spec into [targetDir].
     *
     * @param force When `false`, aborts if [targetDir] already exists and is non-empty.
     */
    fun scaffold(
        category: String,
        spec: PluginInitSpec,
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
        val vars = spec.toVars()
        for (tmpl in templateFiles(category)) {
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
            PluginScaffolder::class.java.classLoader.getResourceAsStream(path)
                ?: throw IllegalStateException(
                    "Missing classpath resource: $path — this is a bug in kuml-cli",
                )
        return stream.use { it.bufferedReader().readText() }
    }
}

// ── Command ───────────────────────────────────────────────────────────────────

/**
 * `kuml plugin init` — scaffolds a new kUML plugin project.
 *
 * Usage:
 * ```
 * kuml plugin init theme --id com.example.my-theme --name "My Theme" --maintainer "Alice"
 * kuml plugin init --category renderer --id com.example.pdf --non-interactive
 * ```
 */
internal class PluginInitCommand : CliktCommand(name = "init") {
    private val categoryArg: String? by argument(
        help = "Plugin category: theme, renderer, layout, codegen, reverse",
    ).choice("theme", "renderer", "layout", "codegen", "reverse").optional()

    private val categoryOpt: String? by option(
        "--category",
        help = "Plugin category (alternative to positional argument)",
    ).choice("theme", "renderer", "layout", "codegen", "reverse")

    private val id: String? by option("--id", help = "Plugin id, e.g. com.example.my-theme")
    private val name: String? by option("--name", help = "Human-readable plugin name")
    private val maintainer: String? by option("--maintainer", help = "Maintainer name or organisation")
    private val homepage: String? by option("--homepage", help = "Plugin homepage URL")
    private val license: String by option("--license", help = "SPDX license id").default("Apache-2.0")
    private val output: File? by option(
        "--output",
        help = "Target directory (default: ./<artifact-id>)",
    ).file(canBeFile = false)
    private val nonInteractive: Boolean by option(
        "--non-interactive",
        "-y",
        help = "Fail instead of prompting for missing values",
    ).flag(default = false)
    private val force: Boolean by option(
        "--force",
        help = "Overwrite existing target directory",
    ).flag(default = false)

    override fun help(context: Context): String = "Scaffold a new kUML plugin project. Specify a category as argument or via --category."

    override fun run() {
        val category = categoryArg ?: categoryOpt ?: promptCategory()

        val pluginId = id ?: promptRequired("id", "Plugin id (e.g. com.example.my-$category)")
        val pluginName = name ?: promptRequired("name", "Plugin name (e.g. My ${category.replaceFirstChar { it.uppercaseChar() }})")
        val maintainerVal = maintainer ?: promptRequired("maintainer", "Maintainer name")
        val homepageVal = homepage ?: if (nonInteractive) "" else currentContext.terminal.prompt("Homepage URL", default = "") ?: ""

        val spec =
            PluginInitSpec.from(
                category = category,
                pluginId = pluginId,
                pluginName = pluginName,
                maintainer = maintainerVal,
                homepage = homepageVal,
                licenseSpdx = license,
            )

        val targetDir = (output ?: File(spec.artifactId)).absoluteFile
        try {
            PluginScaffolder.scaffold(category, spec, targetDir, force, ::echo)
        } catch (e: IllegalStateException) {
            echo("Error: ${e.message}", err = true)
            throw ProgramResult(ExitCodes.IO_ERROR)
        }

        echo("")
        echo("Plugin project created at: ${targetDir.absolutePath}")
        echo("  Build:   cd ${targetDir.name} && ./gradlew build")
        echo("  Install: kuml plugin install build/libs/${spec.artifactId}-1.0.0.jar")
    }

    /**
     * Prompts the user for the plugin category in interactive mode via Clikt's terminal abstraction,
     * re-prompting until a valid value from [VALID_CATEGORIES] is entered.
     * Fails immediately in --non-interactive mode.
     */
    private fun promptCategory(): String {
        if (nonInteractive) {
            echo("Error: --category is required in --non-interactive mode.", err = true)
            throw ProgramResult(ExitCodes.USAGE)
        }
        while (true) {
            val input =
                currentContext.terminal.prompt("Plugin category (theme/renderer/layout/codegen/reverse)")
                    ?: run {
                        echo("Error: category is required.", err = true)
                        throw ProgramResult(ExitCodes.USAGE)
                    }
            val trimmed = input.trim()
            if (trimmed in VALID_CATEGORIES) return trimmed
            echo("Unknown category '$trimmed'. Valid values: ${VALID_CATEGORIES.joinToString(", ")}", err = true)
        }
    }

    /**
     * Prompts the user for a required value via Clikt's terminal abstraction, so it is testable
     * with [com.github.ajalt.clikt.testing.CliktCommand.test].
     * Fails immediately in --non-interactive mode.
     */
    private fun promptRequired(
        optionName: String,
        promptText: String,
    ): String {
        if (nonInteractive) {
            echo("Error: --$optionName is required in --non-interactive mode.", err = true)
            throw ProgramResult(ExitCodes.USAGE)
        }
        return currentContext.terminal.prompt(promptText)?.takeIf { it.isNotBlank() }
            ?: run {
                echo("Error: $optionName is required.", err = true)
                throw ProgramResult(ExitCodes.USAGE)
            }
    }
}
