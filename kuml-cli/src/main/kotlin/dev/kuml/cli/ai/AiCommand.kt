package dev.kuml.cli.ai

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import dev.kuml.ai.provider.ProviderRegistry
import dev.kuml.cli.ExitCodes
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ─────────────────────────────────────────────────────────────────────────────
// `kuml ai` subcommand group
//
// Sub-subcommands:
//   kuml ai provider list   — table of registered LLM providers
//   kuml ai provider info   — details + models for one provider
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Top-level `kuml ai` subcommand.
 *
 * Currently exposes a `provider` sub-group for inspecting registered LLM providers.
 * Execution commands (e.g. kuml ai ask, kuml ai edit) are planned for V3.2+.
 */
internal class AiCommand : CliktCommand(name = "ai") {
    init {
        subcommands(AiProviderCommand())
    }

    override fun help(context: Context): String = "Inspect and manage kUML AI provider configuration."

    override fun run() = Unit
}

// ── `kuml ai provider` ────────────────────────────────────────────────────────

internal class AiProviderCommand : CliktCommand(name = "provider") {
    init {
        subcommands(AiProviderListCommand(), AiProviderInfoCommand())
    }

    override fun help(context: Context): String = "List and inspect registered LLM providers."

    override fun run() = Unit
}

// ── `kuml ai provider list` ──────────────────────────────────────────────────

/** JSON shape for `--output json`. */
@Serializable
private data class ProviderListJson(
    val providers: List<ProviderListEntry>,
)

@Serializable
private data class ProviderListEntry(
    val id: String,
    val displayName: String,
    val local: Boolean,
    val modelCount: Int,
)

internal class AiProviderListCommand : CliktCommand(name = "list") {
    private val outputFormat by option("-o", "--output", help = "Output format (text or json)")
        .choice("text", "json")
        .default("text")

    override fun help(context: Context): String = "List all registered kUML LLM providers (built-in + custom SPI)."

    override fun run() {
        val providers = ProviderRegistry.discover().all().sortedBy { it.id }

        when (outputFormat) {
            "json" -> {
                val items =
                    providers.map {
                        ProviderListEntry(
                            id = it.id,
                            displayName = it.displayName,
                            local = it.isLocal,
                            modelCount = it.supportedModels.size,
                        )
                    }
                echo(Json { prettyPrint = true }.encodeToString(ProviderListJson(items)))
            }
            else -> {
                if (providers.isEmpty()) {
                    echo("No providers registered.")
                    return
                }
                echo("Registered LLM providers (${providers.size}):\n")

                val idWidth = providers.maxOf { it.id.length }.coerceAtLeast(10)
                val nameWidth = providers.maxOf { it.displayName.length }.coerceAtLeast(12)
                val header = "%-${idWidth}s  %-${nameWidth}s  %-8s  MODELS".format("ID", "NAME", "TYPE")
                echo(header)
                echo("-".repeat(header.length))

                for (p in providers) {
                    val typeBadge = if (p.isLocal) "local" else "cloud"
                    val modelStr = if (p.supportedModels.isEmpty()) "(dynamic)" else p.supportedModels.size.toString()
                    echo(
                        "%-${idWidth}s  %-${nameWidth}s  %-8s  %s".format(
                            p.id,
                            p.displayName,
                            typeBadge,
                            modelStr,
                        ),
                    )
                }
            }
        }
    }
}

// ── `kuml ai provider info` ──────────────────────────────────────────────────

internal class AiProviderInfoCommand : CliktCommand(name = "info") {
    private val id by argument(help = "Provider id (e.g. openai, anthropic, google, ollama)")

    override fun help(context: Context): String = "Show details and supported models for a registered LLM provider."

    override fun run() {
        val registry = ProviderRegistry.discover()
        val provider =
            registry.get(id)
                ?: run {
                    System.err.println("Provider not found: '$id'")
                    System.err.println(
                        "Registered providers: ${registry.all().map { it.id }.sorted().joinToString(", ")}",
                    )
                    throw ProgramResult(ExitCodes.PLUGIN_NOT_FOUND)
                }

        echo("Provider: ${provider.displayName}")
        echo("  ID:      ${provider.id}")
        val typeStr =
            if (provider.isLocal) {
                "local — no data leaves this machine"
            } else {
                "cloud — prompts are sent to a third-party API"
            }
        echo("  Type:    $typeStr")
        echo("  Privacy: ${privacyLine(provider.isLocal, provider.displayName)}")
        if (provider.isCustomProvider) {
            echo("  Note:    Custom SPI provider (read-only in V3.1.15 — not yet executable)")
        }

        if (provider.supportedModels.isEmpty()) {
            echo("  Models:  (dynamic — any model id pulled locally is accepted)")
        } else {
            echo("  Models (${provider.supportedModels.size}):\n")
            val idWidth = provider.supportedModels.maxOf { it.modelId.length }.coerceAtLeast(12)
            val nameWidth = provider.supportedModels.maxOf { it.displayName.length }.coerceAtLeast(12)
            echo("  %-${idWidth}s  %-${nameWidth}s  CONTEXT WINDOW".format("MODEL ID", "NAME"))
            echo("  " + "-".repeat(idWidth + nameWidth + 20))
            for (m in provider.supportedModels) {
                val ctx = m.contextWindowTokens?.let { formatTokens(it) } ?: "unknown"
                echo("  %-${idWidth}s  %-${nameWidth}s  %s".format(m.modelId, m.displayName, ctx))
            }
        }
    }

    private fun privacyLine(
        isLocal: Boolean,
        displayName: String,
    ): String =
        if (isLocal) {
            "Safe — inference runs locally, no data sent to third parties"
        } else {
            "Review provider's privacy policy — your kUML content is sent to $displayName"
        }

    private fun formatTokens(n: Int): String =
        when {
            n >= 1_000_000 -> "${n / 1_000_000}.${(n % 1_000_000) / 100_000}M tokens"
            n >= 1_000 -> "${n / 1_000}K tokens"
            else -> "$n tokens"
        }
}
