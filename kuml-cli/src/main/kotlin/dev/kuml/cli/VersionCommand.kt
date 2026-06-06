package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The `version` subcommand — same data as `kuml --version` but with a
 * structured output mode for scripts.
 *
 * Plain text (default):
 * ```
 * kuml 0.3.0 (build: 9c56700, jdk: 21.0.4+8)
 * ```
 *
 * JSON output (`--json`):
 * ```json
 * {
 *   "version": "0.3.0",
 *   "gitSha":  "9c56700",
 *   "jdk":     "21.0.4+8",
 *   "buildTime": "2026-06-06T11:23:45Z"
 * }
 * ```
 *
 * The JSON mode is the contract for tooling consumers (Homebrew tap update
 * scripts, CI version-check steps, the future `kuml update check` against
 * the GitHub Releases API) — extract values with `jq -r .version`,
 * `jq -r .gitSha`, etc.
 */
internal class VersionCommand : CliktCommand(name = "version") {
    private val json by option(
        "--json",
        help = "Emit the version metadata as a single-line JSON object for scripting.",
    ).flag(default = false)

    override fun help(context: Context): String = "Show the kUML CLI version, git SHA, JDK, and build time."

    override fun run() {
        if (json) {
            val payload =
                VersionInfo(
                    version = KumlVersion.version,
                    gitSha = KumlVersion.gitSha,
                    jdk = KumlVersion.jdkVersion,
                    buildTime = KumlVersion.buildTime,
                )
            echo(JSON.encodeToString(payload))
        } else {
            echo(KumlVersion.formatPlain())
        }
    }

    @Serializable
    private data class VersionInfo(
        val version: String,
        val gitSha: String,
        val jdk: String,
        val buildTime: String,
    )

    private companion object {
        // Compact JSON — easier to consume from shell pipelines, no surprise
        // whitespace differences between runs.
        val JSON = Json { prettyPrint = false }
    }
}
