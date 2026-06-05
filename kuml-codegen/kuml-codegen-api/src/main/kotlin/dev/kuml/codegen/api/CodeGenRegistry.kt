package dev.kuml.codegen.api

import java.util.ServiceLoader

/**
 * In-Process-Registry für [KumlCodeGenerator]-Plugins.
 *
 * Generatoren sind über ihre [KumlCodeGenerator.id] adressierbar. Die Registry
 * wird entweder durch explizite [register]-Aufrufe (für Tests) oder via
 * [loadFromClasspath] (für CLI- und MCP-Produktion) befüllt.
 *
 * Pattern-Reuse aus `dev.kuml.profile.ProfileRegistry` und
 * `dev.kuml.renderer.theme.core.ThemeRegistry` (V1.1.3).
 */
public object CodeGenRegistry {
    private val byId = mutableMapOf<String, KumlCodeGeneratorProvider>()

    public fun register(provider: KumlCodeGeneratorProvider) {
        byId[provider.generator().id] = provider
    }

    /** Liefert den Generator mit gegebener ID, oder `null` wenn nicht registriert. */
    public fun get(id: String): KumlCodeGenerator? = byId[id]?.generator()

    /** Alle registrierten Generator-IDs (alphabetisch sortiert). */
    public fun names(): List<String> = byId.keys.toList().sorted()

    /** Test-Hilfsfunktion. */
    public fun clear() {
        byId.clear()
    }

    /** Lädt alle Provider aus dem Classpath via ServiceLoader. */
    public fun loadFromClasspath() {
        ServiceLoader
            .load(KumlCodeGeneratorProvider::class.java)
            .forEach { register(it) }
    }
}
