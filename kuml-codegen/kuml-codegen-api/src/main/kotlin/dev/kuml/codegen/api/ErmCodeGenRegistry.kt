package dev.kuml.codegen.api

import java.util.ServiceLoader

/**
 * In-Process-Registry für [ErmCodeGenerator]-Plugins.
 *
 * Mirrors [CodeGenRegistry] exactly, but for ERM-first generators — a
 * **separate** id-namespace, so an ERM generator and a UML generator may
 * share the same [ErmCodeGenerator.id] / [KumlCodeGenerator.id] (e.g. both
 * are called `"sql"`) without colliding.
 *
 * V3.4.7.
 */
public object ErmCodeGenRegistry {
    private val byId = mutableMapOf<String, ErmCodeGeneratorProvider>()

    public fun register(provider: ErmCodeGeneratorProvider) {
        byId[provider.generator().id] = provider
    }

    /** Liefert den Generator mit gegebener ID, oder `null` wenn nicht registriert. */
    public fun get(id: String): ErmCodeGenerator? = byId[id]?.generator()

    /** Alle registrierten Generator-IDs (alphabetisch sortiert). */
    public fun names(): List<String> = byId.keys.toList().sorted()

    /** Test-Hilfsfunktion. */
    public fun clear() {
        byId.clear()
    }

    /** Lädt alle Provider aus dem Classpath via ServiceLoader. */
    public fun loadFromClasspath() {
        ServiceLoader
            .load(ErmCodeGeneratorProvider::class.java)
            .forEach { register(it) }
    }
}
