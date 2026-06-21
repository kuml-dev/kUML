package dev.kuml.runtime.chain.spec

import kotlinx.serialization.Serializable

/**
 * Definiert eine Contract-Funktion des KumlBackedContract-Interface.
 *
 * @property name Funktionsname (z.B. "modelHash").
 * @property signature Kanonische ABI-Signatur (z.B. "modelHash()").
 * @property stateMutability Solidity-State-Mutability: "view", "pure", "nonpayable" oder "payable".
 * @property description Menschenlesbare Beschreibung.
 */
@Serializable
public data class ContractFunctionSpec(
    public val name: String,
    public val signature: String,
    public val stateMutability: String,
    public val description: String,
)
