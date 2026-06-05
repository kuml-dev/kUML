package dev.kuml.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Konfigurationsblock `kuml { … }` im `build.gradle.kts`.
 *
 * ```kotlin
 * kuml {
 *     sourceDir.set(file("src/main/kuml"))
 *     outputDir.set(layout.buildDirectory.dir("kuml"))
 *     format.set("svg")                 // "svg" | "png"
 *     theme.set("kuml")                 // beliebiger ThemeRegistry-Name
 *     widthPx.set(1024)                 // nur fuer PNG
 *     generator.set("kotlin")           // beliebiger CodeGenRegistry-Name
 *     generatePackage.set("com.example.domain")
 *     generateOptions.put("java-style", "records")
 *     failOnValidationViolations.set(true)
 * }
 * ```
 *
 * Alle Felder sind Gradle-`Property`/`DirectoryProperty` und damit lazy.
 */
public abstract class KumlExtension
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        /** Wurzel-Ordner, der nach `*.kuml.kts` durchsucht wird. */
        public val sourceDir: DirectoryProperty = objects.directoryProperty()

        /** Ziel-Ordner fuer die Render-/Generate-Artefakte. */
        public val outputDir: DirectoryProperty = objects.directoryProperty()

        /** Render-Format: `"svg"` (Default) oder `"png"`. */
        public val format: Property<String> = objects.property(String::class.java).convention("svg")

        /** Theme-Name (siehe `ThemeRegistry`). Default `"plain"`. */
        public val theme: Property<String> = objects.property(String::class.java).convention("plain")

        /** Pixelbreite fuer PNG-Render. Default 1024. */
        public val widthPx: Property<Int> = objects.property(Int::class.java).convention(1024)

        /** Codegen-Plugin-Name (siehe `CodeGenRegistry`). Default `"kotlin"`. */
        public val generator: Property<String> = objects.property(String::class.java).convention("kotlin")

        /** Paket-Name fuer den Codegen (Java/Kotlin). */
        public val generatePackage: Property<String> = objects.property(String::class.java)

        /** Weitere Codegen-Optionen (z.B. `java-style=records`). */
        public val generateOptions: org.gradle.api.provider.MapProperty<String, String> =
            objects.mapProperty(String::class.java, String::class.java)

        /** Bei `kumlValidate`: bei Violations den Build mit Fehler abbrechen. Default `true`. */
        public val failOnValidationViolations: Property<Boolean> =
            objects.property(Boolean::class.java).convention(true)
    }
