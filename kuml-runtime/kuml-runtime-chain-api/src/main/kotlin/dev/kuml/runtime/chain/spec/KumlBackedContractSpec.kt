package dev.kuml.runtime.chain.spec

import kotlinx.serialization.Serializable

/**
 * V3.0.3 — Maschinenlesbare Referenz-Spezifikation des chain-agnostischen
 * Interface, das ein on-chain KumlBackedContract exponieren MUSS, damit ein
 * [dev.kuml.runtime.chain.KumlChainAdapter] ihn lesen kann.
 *
 * Macht den bisher impliziten Contract von EvmChainAdapter.connect (Selektoren
 * modelHash()/modelUri()/schemaVersion()) explizit und serialisierbar. Dient als
 * Single Source of Truth für Contract-Generatoren und cross-chain-Adapter.
 *
 * Serialisierbar nach JSON (kotlinx.serialization). Native-Image-tauglich.
 */
@Serializable
public data class KumlBackedContractSpec(
    public val schemaVersion: Int,
    public val events: List<ContractEventSpec>,
    public val functions: List<ContractFunctionSpec>,
) {
    public companion object {
        /** Referenz-Spezifikation für Schema-Version 1 (entspricht V3.0.2 EvmChainAdapter). */
        public val V1: KumlBackedContractSpec =
            KumlBackedContractSpec(
                schemaVersion = 1,
                functions =
                    listOf(
                        ContractFunctionSpec(
                            name = "modelHash",
                            signature = "modelHash()",
                            stateMutability = "view",
                            description = "Kanonischer SHA-256-Hash des kUML-Modells (bytes32).",
                        ),
                        ContractFunctionSpec(
                            name = "modelUri",
                            signature = "modelUri()",
                            stateMutability = "view",
                            description = "Auflösbare URI des Modell-Quelltexts (string).",
                        ),
                        ContractFunctionSpec(
                            name = "schemaVersion",
                            signature = "schemaVersion()",
                            stateMutability = "view",
                            description = "On-Chain-Schema-Versions-Diskriminator (uint256).",
                        ),
                    ),
                events =
                    listOf(
                        ContractEventSpec(
                            name = "ModelRegistered",
                            signature = "ModelRegistered(bytes32,string,uint256)",
                            description = "Einmalige Registrierung des Modells beim Deploy.",
                        ),
                        ContractEventSpec(
                            name = "ModelUpdated",
                            signature = "ModelUpdated(bytes32,string)",
                            description = "Aktualisierung von modelHash/modelUri.",
                        ),
                    ),
            )
    }
}
