package dev.kuml.workspace.scaffold

import java.util.Properties

/**
 * Modul-lokaler Spiegel von `dev.kuml.cli.KumlVersion` (V3.x, FT-Desktop-New-Workspace) —
 * `kuml-workspace` darf nicht von `kuml-cli` abhängen (Rückwärtsabhängigkeit), braucht aber
 * denselben `version`-Wert als Default für `WorkspaceInitSpec.kumlVersion`, damit
 * `kuml workspace init` byte-identischen Output zu vorher erzeugt.
 */
internal object WorkspaceKumlVersion {
    val version: String by lazy { props.getProperty("version", "unknown") }

    private val props: Properties by lazy {
        Properties().also { p ->
            WorkspaceKumlVersion::class.java.getResourceAsStream("/dev/kuml/workspace/version.properties")?.use { p.load(it) }
        }
    }
}
