package dev.kuml.cli

import java.util.Properties

/**
 * Build- und Runtime-Versionsdaten der kUML-CLI.
 *
 * Werte werden zur Build-Zeit aus `version.properties` gelesen (siehe
 * `processResources`-Filter in `kuml-cli/build.gradle.kts`). Lazy initialisiert
 * — der Properties-Lookup passiert beim ersten Zugriff, nicht beim Klassen-Load.
 *
 * Wird konsumiert von:
 *  - [KumlCli] über Clikts `versionOption(...)` für `kuml --version`
 *  - [VersionCommand] für `kuml version` (Plain-Text + `--json`)
 */
internal object KumlVersion {
    /** kUML-Version, z. B. `"0.4.0"` oder `"0.4.1-SNAPSHOT"`. */
    val version: String by lazy { props.getProperty("version", "unknown") }

    /** Git-SHA des Builds (kurz), z. B. `"9c56700"` oder `"unknown"` außerhalb eines Git-Trees. */
    val gitSha: String by lazy { props.getProperty("gitSha", "unknown") }

    /** ISO-8601-Zeitstempel des Build-Zeitpunkts. */
    val buildTime: String by lazy { props.getProperty("buildTime", "unknown") }

    /** JDK-Version des laufenden Prozesses (Runtime-Info, nicht aus den Build-Properties). */
    val jdkVersion: String by lazy {
        System.getProperty("java.runtime.version") ?: System.getProperty("java.version") ?: "unknown"
    }

    /**
     * Einzeiliger Plain-Text-Output für `kuml --version`.
     *
     * Beispiel: `kuml 0.3.0 (build: 9c56700, jdk: 21.0.4+8)`
     *
     * Die Form ist Konvention vieler etablierter CLI-Tools (kubectl, gh, brew),
     * sodass Skript-Autoren mit `kuml --version | awk '{print $2}'` die Version
     * extrahieren können.
     */
    fun formatPlain(): String = "kuml $version (build: $gitSha, jdk: $jdkVersion)"

    private val props: Properties by lazy {
        Properties().also { p ->
            KumlVersion::class.java.getResourceAsStream("/dev/kuml/cli/version.properties")?.use { stream ->
                p.load(stream)
            }
        }
    }
}
