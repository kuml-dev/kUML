package dev.kuml.cli

import com.github.ajalt.clikt.completion.CompletionCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import dev.kuml.cli.run.RunCommand
import dev.kuml.cli.sandbox.SandboxCommand
import dev.kuml.cli.trace.TraceCommand
import dev.kuml.cli.update.UpdateCommand

/**
 * Root command for the kUML CLI.
 *
 * Registers all subcommands and delegates execution to them.
 */
internal class KumlCli : CliktCommand(name = "kuml") {
    init {
        // `--version` flag at the root level. Clikt prints the message and exits 0,
        // so we don't need a separate code path. The string format matches the
        // convention used by kubectl, gh, brew etc. — see `KumlVersion.formatPlain`.
        versionOption(version = KumlVersion.version, message = { KumlVersion.formatPlain() })

        subcommands(
            RenderCommand(),
            ServeCommand(),
            WatchCommand(),
            ValidateCommand(),
            ValidateExpressionsCommand(),
            FmtCommand(),
            GenerateCommand(),
            TransformCommand(),
            ImportCommand(),
            ExportCommand(),
            MarkdownCommand(),
            ProfileCommand(),
            SimulateCommand(),
            TraceCommand(),
            SandboxCommand(),
            RunCommand(),
            ReverseCommand(),
            VersionCommand(),
            UpdateCommand(),
            CompletionCommand(name = "completion"),
        )
    }

    override fun help(context: Context): String = "Compiles kUML scripts to UML/C4 diagrams."

    override fun run() = Unit
}
