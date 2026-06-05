package dev.kuml.core.config

@KumlConfigDsl
public class KumlConfigBuilder internal constructor() {
    private var renderBuilder: RenderConfigBuilder? = null

    public fun render(block: RenderConfigBuilder.() -> Unit) {
        val b = renderBuilder ?: RenderConfigBuilder().also { renderBuilder = it }
        b.block()
    }

    internal fun build(): KumlConfig = KumlConfig(render = renderBuilder?.build() ?: RenderConfig())
}

@KumlConfigDsl
public class RenderConfigBuilder internal constructor() {
    private var themesBuilder: ThemesConfigBuilder? = null
    private var stereotypesBuilder: StereotypesConfigBuilder? = null

    public fun themes(block: ThemesConfigBuilder.() -> Unit) {
        val b = themesBuilder ?: ThemesConfigBuilder().also { themesBuilder = it }
        b.block()
    }

    public fun stereotypes(block: StereotypesConfigBuilder.() -> Unit) {
        val b = stereotypesBuilder ?: StereotypesConfigBuilder().also { stereotypesBuilder = it }
        b.block()
    }

    internal fun build(): RenderConfig =
        RenderConfig(
            themeName = themesBuilder?.default,
            stereotypeOverrides = stereotypesBuilder?.build(),
        )
}

@KumlConfigDsl
public class ThemesConfigBuilder internal constructor() {
    /** Name des Default-Themes, das die `RenderPipeline` aus der Registry zieht. */
    public var default: String? = null
}

@KumlConfigDsl
public class StereotypesConfigBuilder internal constructor() {
    public var showTaggedValues: Boolean? = null
    public var showConstraints: Boolean? = null
    public var showIcons: Boolean? = null
    public var joinSeparator: String? = null
    public var headerFontSize: Float? = null
    public var taggedValueFontSize: Float? = null
    public var showFeatureStereotypes: Boolean? = null
    public var featureStereotypeFontSize: Float? = null

    /**
     * Baut einen [StereotypeOverridePatch] aus den explizit gesetzten Slots;
     * nicht gesetzte Slots bleiben null, damit die Pipeline auf das Basis-Theme
     * zurückfällt.
     */
    internal fun build(): StereotypeOverridePatch =
        StereotypeOverridePatch(
            showTaggedValues = showTaggedValues,
            showConstraints = showConstraints,
            showIcons = showIcons,
            joinSeparator = joinSeparator,
            headerFontSize = headerFontSize,
            taggedValueFontSize = taggedValueFontSize,
            showFeatureStereotypes = showFeatureStereotypes,
            featureStereotypeFontSize = featureStereotypeFontSize,
        )
}

/**
 * Top-Level-Einstieg in die Config-DSL.
 *
 * Muss die letzte Expression in einem `*.kuml.config.kts`-Script sein, damit
 * [KumlConfigScriptHost] den Rückgabewert extrahieren kann.
 */
public fun kumlConfig(block: KumlConfigBuilder.() -> Unit): KumlConfig = KumlConfigBuilder().apply(block).build()
