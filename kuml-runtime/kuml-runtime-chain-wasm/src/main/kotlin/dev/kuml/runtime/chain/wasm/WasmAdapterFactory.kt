package dev.kuml.runtime.chain.wasm

import dev.kuml.runtime.chain.KumlChainAdapter

/**
 * V3.0.22 — ServiceLoader-discoverbare Factory fuer WASM-Contract-Adapter.
 *
 * Plugin-Autoren registrieren ihre eigene [WasmAdapterFactory]-Implementierung via
 * `META-INF/services/dev.kuml.runtime.chain.wasm.WasmAdapterFactory`. Die kUML-Runtime
 * laedt alle Factories per [java.util.ServiceLoader] und waehlt anhand von [supportsChainId]
 * die passende aus — so kann ein Plugin-Autor mit den bereitgestellten Bausteinen
 * (ScaleCodec, InkAbiMetadata, InkEventDecoder) einen eigenen Adapter fuer eine beliebige
 * WASM-/Substrate-Kette beisteuern, ohne diesen Modul-Code zu aendern.
 *
 * Diese Datei liefert zusaetzlich die Default-Factory [SubstrateInkFactory] fuer generische
 * ink!-Contracts auf Substrate-Ketten, sowie [CosmWasmBridgeFactory] als sekundaeres Ziel
 * (delegiert via Bridge-Pattern an den Cosmos-Adapter, falls dieser im Classpath liegt).
 */
public interface WasmAdapterFactory {
    /** Eindeutiger Bezeichner dieser Factory (z.B. "substrate-ink", "cosmwasm"). */
    public val factoryId: String

    /**
     * Prueft, ob diese Factory die gegebene Chain-ID/Family bedienen kann.
     *
     * @param chainId Kennung wie "polkadot", "kusama", "astar", "shiden", "cosmwasm:juno-1".
     */
    public fun supportsChainId(chainId: String): Boolean

    /** Erzeugt eine frische, noch nicht verbundene [KumlChainAdapter]-Instanz. */
    public fun create(): KumlChainAdapter

    public companion object {
        /**
         * Laedt alle via ServiceLoader registrierten Factories und liefert die erste, die
         * [supportsChainId] fuer [chainId] mit true beantwortet.
         *
         * @return passende Factory oder null wenn keine registriert ist.
         */
        public fun forChain(chainId: String): WasmAdapterFactory? {
            val loader = java.util.ServiceLoader.load(WasmAdapterFactory::class.java)
            return loader.firstOrNull { it.supportsChainId(chainId) }
        }

        /** Liste aller registrierten Factory-IDs (Diagnostik / Plugin-Discovery). */
        public fun availableFactoryIds(): List<String> =
            java.util.ServiceLoader
                .load(WasmAdapterFactory::class.java)
                .map { it.factoryId }
    }
}

/**
 * Default-Factory fuer generische ink!-Contracts auf Substrate-Ketten.
 * Wird via `META-INF/services` registriert und deckt die gaengigen ink!-faehigen Parachains ab.
 */
public class SubstrateInkFactory : WasmAdapterFactory {
    override val factoryId: String = "substrate-ink"

    private val knownInkChains =
        setOf("polkadot", "kusama", "astar", "shiden", "shibuya", "aleph-zero", "phala", "substrate-contracts")

    override fun supportsChainId(chainId: String): Boolean {
        val norm = chainId.lowercase()
        return norm in knownInkChains || norm.startsWith("substrate:") || norm.startsWith("ink:")
    }

    override fun create(): KumlChainAdapter = SubstrateWasmAdapter()
}

/**
 * Sekundaeres Ziel: Bridge zum CosmWasm-Adapter (Repo-Modul kuml-runtime-chain-cosmos).
 *
 * **Bridge-Pattern statt direkter Abhaengigkeit**: dieses Modul haengt NICHT zur Compile-Zeit
 * von kuml-runtime-chain-cosmos ab (sonst entstuende ein Modul-Zyklus / aufgeblaehter
 * Native-Footprint). Stattdessen wird der Cosmos-Adapter zur Laufzeit reflexiv geladen, falls
 * er im Classpath vorhanden ist. Fehlt er, liefert [supportsChainId] zwar weiterhin true fuer
 * "cosmwasm:*", aber [create] wirft eine erklaerende [IllegalStateException].
 *
 * So bleibt der WASM-Adapter primaer auf ink!/Substrate fokussiert, kann CosmWasm-Ketten aber
 * transparent mitbedienen, sobald das Cosmos-Modul beigesteuert wird.
 */
public class CosmWasmBridgeFactory : WasmAdapterFactory {
    override val factoryId: String = "cosmwasm-bridge"

    override fun supportsChainId(chainId: String): Boolean = chainId.lowercase().startsWith("cosmwasm:")

    override fun create(): KumlChainAdapter {
        val cls =
            try {
                Class.forName("dev.kuml.runtime.chain.cosmos.CosmWasmChainAdapter")
            } catch (e: ClassNotFoundException) {
                throw IllegalStateException(
                    "CosmWasm support requires the kuml-runtime-chain-cosmos module on the classpath. " +
                        "Add it as a dependency to bridge CosmWasm chains via the WASM adapter.",
                    e,
                )
            }
        return cls.getDeclaredConstructor().newInstance() as KumlChainAdapter
    }
}
