package dev.kuml.cli.update

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * Root for the `kuml update` subcommand tree (V2.0.1).
 *
 *  - `kuml update check`  — query GitHub for the latest release, cache the answer,
 *                            exit-code-signal whether an update is available.
 *  - `kuml update notes`  — print the release-notes markdown for the latest
 *                            (or a specific) version.
 *  - `kuml update apply`  — **deliberately not implemented** in V2. Self-updating
 *                            binaries are a security and atomicity headache; the
 *                            three supported install channels (Homebrew, direct
 *                            download, source build) each carry their own
 *                            update mechanism that's better than what we'd
 *                            roll ourselves. May or may not happen in V3.
 *
 * No work is done by the root command itself — it just dispatches.
 */
internal class UpdateCommand : CliktCommand(name = "update") {
    init {
        subcommands(
            UpdateCheckCommand(),
            UpdateNotesCommand(),
        )
    }

    override fun help(context: Context): String = "Check for and inspect newer kUML releases on GitHub (no self-update)."

    override fun run() = Unit
}
