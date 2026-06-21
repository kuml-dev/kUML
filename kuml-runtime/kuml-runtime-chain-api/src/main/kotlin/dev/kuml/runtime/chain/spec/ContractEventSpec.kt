package dev.kuml.runtime.chain.spec

import kotlinx.serialization.Serializable

/**
 * Definiert ein Contract-Event des KumlBackedContract-Interface.
 *
 * @property name Logischer Event-Name (z.B. "ModelUpdated").
 * @property signature Kanonische ABI-Signatur, z.B. "ModelUpdated(bytes32,string)".
 *   Format: Name '(' kommaseparierte ABI-Typen ')' — keine Leerzeichen, keine Param-Namen.
 * @property description Menschenlesbare Beschreibung.
 */
@Serializable
public data class ContractEventSpec(
    public val name: String,
    public val signature: String,
    public val description: String,
)
