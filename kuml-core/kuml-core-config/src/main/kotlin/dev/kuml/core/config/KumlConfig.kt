package dev.kuml.core.config

/**
 * Top-Level-Konfiguration für eine kUML-CLI-Session.
 *
 * Wird typischerweise aus `kuml.config.kts` geladen und über die DSL
 * [kumlConfig] gebaut. Mehrere Configs lassen sich via [merge] schichten —
 * CLI-Flags überstimmen Datei, Datei überstimmt Default.
 *
 * Beispiel `kuml.config.kts`:
 * ```kotlin
 * kumlConfig {
 *     render {
 *         themes { default = "kuml" }
 *         stereotypes {
 *             showTaggedValues = true
 *             showFeatureStereotypes = false
 *         }
 *     }
 * }
 * ```
 */
public data class KumlConfig(
    public val render: RenderConfig = RenderConfig(),
) {
    /**
     * Schichtet `override` auf `this` — nicht-null-Felder von `override`
     * überschreiben die entsprechenden Felder von `this`. Pure Funktion.
     */
    public fun merge(override: KumlConfig): KumlConfig = KumlConfig(render = render.merge(override.render))

    public companion object {
        /** Leere Default-Konfiguration. */
        public val DEFAULT: KumlConfig = KumlConfig()
    }
}

/**
 * Konfiguration für den Render-Pfad.
 *
 * @property themeName Name des registrierten Themes, oder `null` für CLI-Default.
 * @property stereotypeOverrides Optional: Overrides für StereotypeTheme-Slots.
 *   Wird in `RenderPipeline` auf das Basis-Theme angewendet.
 */
public data class RenderConfig(
    public val themeName: String? = null,
    public val stereotypeOverrides: StereotypeOverridePatch? = null,
) {
    public fun merge(override: RenderConfig): RenderConfig =
        RenderConfig(
            themeName = override.themeName ?: themeName,
            stereotypeOverrides = override.stereotypeOverrides ?: stereotypeOverrides,
        )
}
