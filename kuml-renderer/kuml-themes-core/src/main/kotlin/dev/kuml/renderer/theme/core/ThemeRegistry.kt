package dev.kuml.renderer.theme.core

import java.util.ServiceLoader

/**
 * In-Process-Registry für [KumlThemeProvider]-Instanzen.
 *
 * Themes sind über ihren [KumlThemeProvider.name] adressierbar. Die Registry
 * wird entweder durch explizite [register]-Aufrufe (für Tests) oder via
 * [loadFromClasspath] (für CLI-Produktion) befüllt.
 *
 * Pattern-Reuse aus `dev.kuml.profile.ProfileRegistry`.
 */
public object ThemeRegistry {
    private val byName = mutableMapOf<String, KumlThemeProvider>()

    public fun register(provider: KumlThemeProvider) {
        byName[provider.name] = provider
    }

    /** Liefert das Theme mit gegebenem Namen, oder `null` wenn nicht registriert. */
    public fun get(name: String): KumlTheme? = byName[name]?.theme()

    /** Alle registrierten Theme-Namen (alphabetisch sortiert). */
    public fun names(): List<String> = byName.keys.toList().sorted()

    /** Test-Hilfsfunktion. */
    public fun clear() {
        byName.clear()
    }

    /** Lädt alle Provider aus dem Classpath via ServiceLoader. */
    public fun loadFromClasspath() {
        ServiceLoader
            .load(KumlThemeProvider::class.java)
            .forEach { register(it) }
    }
}
