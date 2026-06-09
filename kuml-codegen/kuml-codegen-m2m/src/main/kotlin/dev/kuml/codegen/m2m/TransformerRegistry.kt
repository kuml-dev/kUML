package dev.kuml.codegen.m2m

import java.util.ServiceLoader

/**
 * In-process registry for [KumlTransformer] instances.
 *
 * Transformers are addressable by their stable [KumlTransformer.id]. The registry
 * is populated either via explicit [register] calls (in tests or programmatic usage)
 * or via [loadFromClasspath] which uses [ServiceLoader] to discover all
 * [KumlTransformerProvider] implementations on the classpath.
 *
 * Pattern mirrors `CodeGenRegistry` and `ProfileRegistry` used elsewhere in kUML.
 */
public object TransformerRegistry {
    private val registered = mutableMapOf<String, KumlTransformer<*, *>>()

    /** Registers a transformer under its [KumlTransformer.id]. */
    public fun register(transformer: KumlTransformer<*, *>) {
        registered[transformer.id] = transformer
    }

    /**
     * Returns the transformer for the given [id], cast to `KumlTransformer<S, T>`.
     *
     * Returns `null` if no transformer with that id is registered.
     * The unchecked cast is safe when the caller knows the expected types.
     */
    @Suppress("UNCHECKED_CAST")
    public fun <S, T> get(id: String): KumlTransformer<S, T>? = registered[id] as? KumlTransformer<S, T>

    /** All registered transformer ids, sorted alphabetically. */
    public fun ids(): List<String> = registered.keys.sorted()

    /** Map of id → description for all registered transformers. */
    public fun descriptions(): Map<String, String> = registered.mapValues { it.value.description }

    /** Clears all registrations — intended for test isolation. */
    public fun clear() {
        registered.clear()
    }

    /**
     * Loads all [KumlTransformerProvider] implementations from the classpath
     * via [ServiceLoader] and registers their transformers.
     */
    public fun loadFromClasspath() {
        ServiceLoader.load(KumlTransformerProvider::class.java).forEach {
            register(it.transformer())
        }
    }
}

/**
 * SPI interface for ServiceLoader-based transformer registration.
 *
 * Implementations register via
 * `META-INF/services/dev.kuml.codegen.m2m.KumlTransformerProvider`.
 */
public interface KumlTransformerProvider {
    public fun transformer(): KumlTransformer<*, *>
}
