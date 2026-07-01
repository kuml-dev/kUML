package dev.kuml.renderer.theme.core

/**
 * SPI für Theme-Registrierung via [java.util.ServiceLoader].
 *
 * Implementierungen leben typischerweise im selben Modul wie das Theme selbst
 * und melden sich über `META-INF/services/dev.kuml.renderer.theme.core.KumlThemeProvider` an.
 * Die [ThemeRegistry] entdeckt sie via [ThemeRegistry.loadFromClasspath].
 *
 * Pattern-Reuse aus `dev.kuml.profile.KumlProfileProvider`.
 */
public interface KumlThemeProvider {
    /** Eindeutiger Name, über den das Theme im CLI/Config angesprochen wird. */
    public val name: String

    /** Liefert eine Theme-Instanz. Wird bei jedem `ThemeRegistry.get(...)`-Aufruf gerufen. */
    public fun theme(): KumlTheme
}
