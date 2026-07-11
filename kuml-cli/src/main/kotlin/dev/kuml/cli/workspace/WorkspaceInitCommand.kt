package dev.kuml.cli.workspace

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.mordant.terminal.prompt
import dev.kuml.cli.ExitCodes
import dev.kuml.cli.KumlVersion
import dev.kuml.cli.scaffold.Scaffolder
import dev.kuml.cli.scaffold.TemplateFile
import dev.kuml.workspace.OkfType
import java.io.File

// ── Data model ────────────────────────────────────────────────────────────────

/**
 * Resolved specification for a `workspace init` scaffold (V3.6.2, FT-4).
 *
 * All derived fields are computed by [WorkspaceInitSpec.Companion.from].
 */
internal data class WorkspaceInitSpec(
    val name: String,
    val slug: String,
    val mode: String,
    val kumlVersion: String = KumlVersion.version,
    val vocabularyVersion: String = OkfType.VOCABULARY_VERSION,
    val okfVersion: String = "0.1",
) {
    fun toVars(): Map<String, String> =
        mapOf(
            "name" to name,
            "nameKotlinLiteral" to escapeKotlinStringLiteral(name),
            "slug" to slug,
            "mode" to mode,
            "kumlVersion" to kumlVersion,
            "vocabularyVersion" to vocabularyVersion,
            "okfVersion" to okfVersion,
        )

    companion object {
        private val SLUG_REGEX = Regex("[^a-z0-9]+")

        fun from(
            name: String,
            mode: String,
        ): WorkspaceInitSpec =
            WorkspaceInitSpec(
                name = name,
                slug = slugify(name),
                mode = mode,
            )

        /**
         * Lowercases [name], replaces any run of non-`[a-z0-9]` characters with a single `-`,
         * trims leading/trailing `-`, and falls back to `"workspace"` if nothing safe remains
         * (e.g. a name of just `"!!!"` or an empty string). Used both as the scaffold's default
         * output directory name and inside generated file content/paths (e.g.
         * `{{slug}}.kuml.kts` in engineering mode) — restricting the result to `[a-z0-9-]`
         * means it can never contain `/`, `\`, or `..` and therefore cannot escape the target
         * directory when substituted into an output path.
         */
        fun slugify(name: String): String {
            val cleaned =
                name
                    .lowercase()
                    .replace(SLUG_REGEX, "-")
                    .trim('-')
            return cleaned.ifEmpty { "workspace" }
        }

        /**
         * Escapes [value] for safe interpolation inside a Kotlin double-quoted string literal.
         *
         * Templates that embed `{{name}}` inside compiled kUML DSL script blocks (e.g.
         * `classDiagram(name = "{{nameKotlinLiteral}}")` in `main.kuml.kts.tmpl` and the
         * fenced ` ```kuml ` block in `domain-classes.md.tmpl`) must use this escaped variant
         * instead of the raw `{{name}}`. Without it, a workspace name containing a double
         * quote or backslash produces invalid Kotlin source (unbalanced string literal), and a
         * `$` is parsed as the start of a Kotlin string template (`$foo` / `${expr}`) — both
         * break the documented "passes validate with zero findings and renders cleanly"
         * invariant. Newlines/carriage returns/tabs are also escaped since a raw line break
         * inside a single-quoted Kotlin string literal is a compile error.
         *
         * Backslashes are escaped first so the backslashes introduced by the later
         * replacements are not themselves re-escaped.
         */
        fun escapeKotlinStringLiteral(value: String): String =
            value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
    }
}

// ── Scaffolder ────────────────────────────────────────────────────────────────

internal object WorkspaceScaffolder {
    private const val RESOURCE_BASE = "workspace-templates"

    /** Ordered list of template files for each mode. GraalVM-safe — no classpath directory scan. */
    fun templateFiles(mode: String): List<TemplateFile> {
        val base = "$RESOURCE_BASE/$mode"
        return when (mode) {
            "knowledge" ->
                listOf(
                    TemplateFile("$base/kuml-workspace.toml.tmpl", ".kuml-workspace.toml"),
                    TemplateFile("$base/index.md.tmpl", "index.md"),
                    TemplateFile("$base/introduction.md.tmpl", "articles/01-introduction.md"),
                    TemplateFile("$base/domain-classes.md.tmpl", "models/domain-classes.md"),
                    TemplateFile("$base/glossary.md.tmpl", "glossary/index.md"),
                )
            "engineering" ->
                listOf(
                    TemplateFile("$base/kuml-workspace.toml.tmpl", ".kuml-workspace.toml"),
                    TemplateFile("$base/main.kuml.kts.tmpl", "{{slug}}.kuml.kts"),
                    TemplateFile("$base/gitignore.tmpl", ".gitignore"),
                )
            else -> error("Unknown workspace mode '$mode' — this is a bug in kuml-cli")
        }
    }

    /**
     * Renders and writes all template files for [spec]'s mode into [targetDir], via the
     * shared [Scaffolder] engine (same mechanics as `kuml plugin init`).
     *
     * @param force When `false`, aborts if [targetDir] already exists and is non-empty.
     */
    fun scaffold(
        spec: WorkspaceInitSpec,
        targetDir: File,
        force: Boolean,
        echo: (String) -> Unit,
    ) {
        Scaffolder.scaffold(templateFiles(spec.mode), spec.toVars(), targetDir, force, echo)
    }
}

// ── Command ───────────────────────────────────────────────────────────────────

/**
 * `kuml workspace init` — scaffolds a new OKF knowledge workspace (ADR-0011, V3.6.2 / FT-4).
 *
 * Usage:
 * ```
 * kuml workspace init --name "Muster Verein" --mode knowledge
 * kuml workspace init --mode engineering --name "My Diagrams" --non-interactive
 * ```
 */
internal class WorkspaceInitCommand : CliktCommand(name = "init") {
    private val mode: String by option(
        "--mode",
        help = "Workspace mode: knowledge (prose + diagrams, default) or engineering (bare .kuml.kts scripts)",
    ).choice("knowledge", "engineering").default("knowledge")

    private val name: String? by option("--name", help = "Human-readable workspace name")

    private val output: File? by option(
        "--output",
        help = "Target directory (default: ./<slug>)",
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

    override fun help(context: Context): String = "Scaffold a new OKF knowledge workspace (ADR-0011)."

    override fun run() {
        val workspaceName = name ?: promptRequired("name", "Workspace name (e.g. My Club Bylaws)")

        val spec = WorkspaceInitSpec.from(name = workspaceName, mode = mode)
        val targetDir = (output ?: File(spec.slug)).absoluteFile

        try {
            WorkspaceScaffolder.scaffold(spec, targetDir, force, ::echo)
        } catch (e: IllegalStateException) {
            echo("Error: ${e.message}", err = true)
            throw ProgramResult(ExitCodes.IO_ERROR)
        }

        echo("")
        echo("Workspace created at: ${targetDir.absolutePath}")
        echo("  Validate: kuml workspace validate ${targetDir.absolutePath}")
        echo("  Render:   kuml workspace render ${targetDir.absolutePath}")
    }

    /**
     * Prompts the user for a required value via Clikt's terminal abstraction, so it is testable
     * with [com.github.ajalt.clikt.testing.CliktCommand.test]. Fails immediately in
     * --non-interactive mode.
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
