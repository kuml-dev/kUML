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
import dev.kuml.workspace.scaffold.WorkspaceInitSpec
import dev.kuml.workspace.scaffold.WorkspaceScaffolder
import java.io.File

// ── Command ───────────────────────────────────────────────────────────────────
//
// [WorkspaceInitSpec] and [WorkspaceScaffolder] moved (V3.x, FT-Desktop-New-Workspace) into
// the standalone `kuml-docs:kuml-workspace` module — see their KDoc there for the motivation
// (kuml-desktop's "New Workspace…" dialog needs the same scaffold mechanics without depending
// on kuml-cli). Only the Clikt command itself remains here.

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
        val workspaceName = name ?: promptRequired(optionName = "name", promptText = "Workspace name (e.g. My Club Bylaws)")

        val spec = WorkspaceInitSpec.from(name = workspaceName, mode = mode)
        val targetDir = (output ?: File(spec.slug)).absoluteFile

        try {
            WorkspaceScaffolder.scaffold(spec = spec, targetDir = targetDir, force = force, echo = ::echo)
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
