package dev.kuml.cli

import com.github.ajalt.clikt.completion.CompletionCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import dev.kuml.cli.chain.ChainCommand
import dev.kuml.cli.plugin.PluginCommand
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

        // V3.1.15 — `kuml ai` command group. Guarded: when kuml-ai-core is not on the
        // classpath (built with -Pkuml.noAi=true), the AiCommand class does not exist
        // and we skip registration gracefully.
        val aiCommand =
            runCatching {
                val cls = Class.forName("dev.kuml.cli.ai.AiCommand")
                cls.getDeclaredConstructor().newInstance() as CliktCommand
            }.getOrNull()

        val commands =
            buildList {
                add(RenderCommand())
                add(ServeCommand())
                add(WatchCommand())
                add(ValidateCommand())
                add(ValidateExpressionsCommand())
                add(FmtCommand())
                add(GenerateCommand())
                add(TransformCommand())
                add(ImportCommand())
                add(ExportCommand())
                add(MarkdownCommand())
                add(ProfileCommand())
                add(SimulateCommand())
                add(TraceCommand())
                add(SandboxCommand())
                add(RunCommand())
                add(ReverseCommand())
                add(ChainCommand())
                add(PluginCommand())
                if (aiCommand != null) add(aiCommand) // V3.1.15
                add(VersionCommand())
                add(UpdateCommand())
                add(CompletionCommand(name = "completion"))
            }
        subcommands(commands)
    }

    override fun help(context: Context): String = "Compiles kUML scripts to UML/C4 diagrams."

    override fun run() = Unit
}
