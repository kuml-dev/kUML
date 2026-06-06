package dev.kuml.layout

import java.util.ServiceLoader

/**
 * In-Process-Registry für [KumlLayoutEngineProvider]-Instanzen.
 *
 * Engines sind über ihre [KumlLayoutEngineProvider.id] adressierbar. Die
 * Registry wird entweder durch explizite [register]-Aufrufe (für Tests) oder
 * via [loadFromClasspath] (für CLI/Gradle-Plugin-Produktion) befüllt.
 *
 * **Verwendung im CLI:**
 *
 * ```kotlin
 * if (LayoutEngineRegistry.ids().isEmpty()) LayoutEngineRegistry.loadFromClasspath()
 * val engine = LayoutEngineRegistry.pickFor(diagramKind, defaultEngineId)
 *     ?: error("No engine available")
 * val result = engine.layout(graph, hints)
 * ```
 *
 * Pattern-Reuse aus `dev.kuml.renderer.theme.core.ThemeRegistry` (V1.1.6) und
 * `dev.kuml.codegen.api.CodeGenRegistry` (V1.1.4).
 */
public object LayoutEngineRegistry {
    private val byId = mutableMapOf<LayoutEngineId, KumlLayoutEngineProvider>()

    /** Registriert einen Provider; überschreibt einen bestehenden Eintrag mit derselben ID. */
    public fun register(provider: KumlLayoutEngineProvider) {
        byId[provider.id] = provider
    }

    /** Liefert die Engine mit gegebener ID, oder `null` wenn nicht registriert. */
    public fun get(id: LayoutEngineId): KumlLayoutEngine? = byId[id]?.engine()

    /** Convenience-Overload für String-IDs. */
    public fun get(id: String): KumlLayoutEngine? = get(LayoutEngineId(id))

    /** Alle registrierten Engine-IDs (alphabetisch sortiert nach String-Wert). */
    public fun ids(): List<LayoutEngineId> = byId.keys.sortedBy { it.value }

    /** Test-Hilfsfunktion. */
    public fun clear() {
        byId.clear()
    }

    /** Lädt alle Provider aus dem Classpath via ServiceLoader. */
    public fun loadFromClasspath() {
        ServiceLoader
            .load(KumlLayoutEngineProvider::class.java)
            .forEach { register(it) }
    }

    /**
     * Wählt die passende Engine für einen Diagrammtyp aus.
     *
     * **Auswahllogik** (in dieser Reihenfolge):
     *
     * 1. **Explizite Wahl** durch `preferredEngineId` — wenn der Aufrufer (CLI-
     *    Flag, Config-Datei, DSL-Hint) einen Wunsch hat, gewinnt der. Wir
     *    prüfen NICHT, ob die Engine den Diagrammtyp wirklich unterstützt —
     *    Aufrufer hat das Recht, sich selbst ins Knie zu schießen, und die
     *    Engine-Capabilities sind ohnehin ein weicher Hinweis.
     *
     * 2. **Capability-Match**: erste registrierte Engine, deren
     *    [LayoutCapabilities.supportedDiagramKinds] den [DiagramKind] enthält.
     *
     * 3. **Generic-Fallback**: erste Engine, die [DiagramKind.Generic]
     *    unterstützt.
     *
     * 4. `null` — keine Engine verfügbar (Registry leer / `loadFromClasspath()`
     *    nicht aufgerufen).
     *
     * @param kind Diagrammtyp, für den eine Engine gesucht wird.
     * @param preferredEngineId Optional: explizite Engine-Auswahl (CLI/Config/DSL).
     */
    public fun pickFor(
        kind: DiagramKind,
        preferredEngineId: LayoutEngineId? = null,
    ): KumlLayoutEngine? {
        // 1. Explizite Wahl gewinnt immer
        if (preferredEngineId != null) {
            val explicit = get(preferredEngineId)
            if (explicit != null) return explicit
        }
        // 2. Capability-Match
        val capable =
            byId.values.firstOrNull { provider ->
                kind in provider.engine().capabilities.supportedDiagramKinds
            }
        if (capable != null) return capable.engine()
        // 3. Generic-Fallback
        val generic =
            byId.values.firstOrNull { provider ->
                DiagramKind.Generic in provider.engine().capabilities.supportedDiagramKinds
            }
        return generic?.engine()
    }
}
