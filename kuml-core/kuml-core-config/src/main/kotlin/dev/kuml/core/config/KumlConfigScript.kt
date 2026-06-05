package dev.kuml.core.config

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

/**
 * kUML config script definition.
 *
 * Dateien mit der Endung `*.kuml.config.kts` werden mit dieser Definition
 * kompiliert. Strikte Trennung von `KumlScript`: die Diagramm-DSL ist hier
 * **NICHT** importiert — Config-Scripts dürfen keine Diagramme erzeugen.
 */
@KotlinScript(
    displayName = "kUML Config Script",
    fileExtension = "kuml.config.kts",
    compilationConfiguration = KumlConfigScriptCompilationConfiguration::class,
)
public abstract class KumlConfigScript

public object KumlConfigScriptCompilationConfiguration : ScriptCompilationConfiguration({
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
    defaultImports(
        "dev.kuml.core.config.*",
        "dev.kuml.renderer.theme.core.StereotypeTheme",
    )
})
