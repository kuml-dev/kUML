package dev.kuml.layout

/**
 * SPI für Layout-Engine-Registrierung via [java.util.ServiceLoader].
 *
 * Implementierungen leben typischerweise im selben Modul wie die Engine
 * selbst und melden sich über
 * `META-INF/services/dev.kuml.layout.KumlLayoutEngineProvider` an. Die
 * [LayoutEngineRegistry] entdeckt sie via [LayoutEngineRegistry.loadFromClasspath].
 *
 * Pattern-Reuse aus `dev.kuml.renderer.theme.core.KumlThemeProvider` (V1.1.6),
 * `dev.kuml.codegen.api.KumlCodeGeneratorProvider` (V1.1.4) und
 * `dev.kuml.profile.KumlProfileProvider` (V1.1).
 */
public interface KumlLayoutEngineProvider {
    /**
     * Eindeutige Engine-ID, über die die Engine im CLI/Config angesprochen wird.
     *
     * Wird mit [KumlLayoutEngine.id] abgeglichen — beide müssen identisch sein.
     */
    public val id: LayoutEngineId

    /**
     * Liefert eine Engine-Instanz. Wird bei jedem
     * [LayoutEngineRegistry.get]-Aufruf gerufen.
     *
     * Engines sind nach Vertrag stateless und thread-safe (siehe
     * [KumlLayoutEngine]); Provider können dieselbe Instanz mehrfach
     * zurückgeben oder bei jedem Aufruf neu konstruieren.
     */
    public fun engine(): KumlLayoutEngine
}
