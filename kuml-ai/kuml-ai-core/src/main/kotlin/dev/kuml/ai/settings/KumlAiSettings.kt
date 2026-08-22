package dev.kuml.ai.settings

import kotlinx.serialization.Serializable

/** Persistence schema for kUML AI settings. Stored at the XDG-conformant ai-settings.json path. */
@Serializable
public data class KumlAiSettings(
    /** Schema version — bump on breaking changes; migrations live in KumlAiSettingsStore. */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** Providers the user has enabled (subset of registered providers). */
    val enabledProviders: Set<String> = setOf("ollama"),
    /** Default provider id used when the caller does not specify one. */
    val defaultProvider: String = "ollama",
    /**
     * Default model id per provider. String keys (provider id) → Koog LLModel id.
     * Example: "openai" → "gpt-4o", "anthropic" → "claude-sonnet-4-5", "ollama" → "llama3.2".
     */
    val defaultModels: Map<String, String> =
        mapOf(
            "openai" to "gpt-4o",
            "anthropic" to "claude-sonnet-4-5",
            "google" to "gemini-1.5-pro",
            "ollama" to "llama3.2",
        ),
    /** When true: only local providers (Ollama) may be invoked. Default true (privacy-by-default). */
    val privacyMode: Boolean = true,
    /** Optional soft cap on cumulative spend (USD). When exceeded, executor throws a budget exception (V3.0.26). */
    val costBudgetUsd: Double? = null,
    /** Default system prompt prepended to all prompts (caller may override). */
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /** Temperature default applied when caller does not specify. */
    val temperature: Double = 0.2,
    /**
     * V3.7.5 (review fix) — true once the user has dismissed the AI Providers dialog's
     * leftover-shared-Keychain-item notice. The underlying macOS Keychain item is deliberately
     * never deleted (see `MacOsKeychainBackend.legacySharedItemExists`'s KDoc), so presence
     * alone can never clear the notice on its own — without this flag it would keep
     * reappearing on every dialog open forever, even after the user has already re-entered
     * every affected API key. Not schema-version-gated: a settings file saved before this field
     * existed simply decodes it as `false` (kotlinx.serialization default), which is exactly
     * the desired "not yet dismissed" behaviour for every pre-existing installation.
     */
    val legacyKeychainNoticeDismissed: Boolean = false,
) {
    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 2

        /**
         * The exact V1-era default, kept for the schema v1 → v2 migration
         * (see [dev.kuml.ai.settings.KumlAiSettingsStore.migrate]) so a settings file that
         * still carries this literal value can be told apart from one where the user typed
         * their own custom system prompt.
         */
        internal const val LEGACY_V1_DEFAULT_SYSTEM_PROMPT: String =
            "You are a kUML modelling assistant. " +
                "Prefer typed DSL operations over freeform text. " +
                "When patching models, use the smallest possible diff."

        /**
         * V3.2.x — real tool-calling: names the actual kUML editing/inspection tools
         * ([dev.kuml.ai.tools.registry.KumlToolRegistry.forUml]) and shows a real snippet of
         * the kUML DSL grammar, so the model stops inventing PlantUML/Mermaid-flavoured syntax
         * (design review). Example adapted from
         * `03 Bereiche/kUML/Beispiele/01 UML Klasse – Order Domain` in the vault — kept as a
         * small inline excerpt here (this module is a plain JVM library with no vault access
         * at runtime) rather than a live read; if the vault example's DSL shape drifts, revisit
         * this constant too.
         */
        public val DEFAULT_SYSTEM_PROMPT: String =
            """
            You are a kUML modelling assistant embedded in the kUML Desktop application.
            You have tools to directly create and edit UML class diagrams: add_class,
            add_interface, add_attribute, add_operation, add_association,
            add_generalization, remove_element, rename_element, set_current_diagram —
            plus read-only tools to inspect and check your work: list_elements,
            get_element_details, find_unused_elements, render_preview, validate_model.

            ALWAYS prefer calling these tools over describing changes in prose. Typical
            order: add_class/add_interface to create classifiers, then add_attribute /
            add_operation to fill them in, then add_association / add_generalization to
            connect them. Call list_elements first if you are unsure an element already
            exists.

            kUML's diagram language is a Kotlin DSL — NOT PlantUML, NOT Mermaid, and NOT
            any other diagram syntax. A minimal class diagram with an enum looks exactly
            like this:

            ```kotlin
            classDiagram(name = "People") {
                val gender = enumOf(name = "Gender") {
                    literal(name = "MALE")
                    literal(name = "FEMALE")
                }
                val person = classOf(name = "Person") {
                    attribute(name = "name", type = "String")
                    attribute(name = "gender", type = gender)
                }
            }
            ```

            If tool calling is unavailable or the call fails, respond with valid kUML DSL
            in a single ```kotlin fenced code block that the user can paste directly into
            the editor — never PlantUML, Mermaid, or invented pseudo-syntax. When patching
            models via tools, use the smallest possible number of tool calls.
            """.trimIndent()
    }
}
