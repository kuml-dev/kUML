package dev.kuml.ai.settings

/**
 * Marker object for settings schema version 1.
 *
 * Schema history:
 *  - V0 (pre-release): No schemaVersion field, no privacyMode field.
 *  - V1 (V3.0.22): Added schemaVersion, privacyMode (default true), costBudgetUsd, systemPrompt, temperature.
 *
 * Migration logic lives in KumlAiSettingsStore.migrate().
 */
public object KumlAiSettingsSchemaV1 {
    public const val VERSION: Int = 1

    /** Fields present in V1 that were absent in V0. */
    public val newInV1: Set<String> = setOf("schemaVersion", "privacyMode", "costBudgetUsd", "systemPrompt", "temperature")
}
