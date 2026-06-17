package dev.kuml.plugin.loader.scan

import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Resolves plugin scan directories from well-known locations.
 *
 * Priority order (first wins for built-ins, all scanned for external):
 * 1. `$KUML_HOME/plugins/`   — system-wide, set via env var
 * 2. `~/.kuml/plugins/`      — user-installed (default)
 * 3. Custom paths passed to [dev.kuml.plugin.loader.loader.PluginLoader.load]
 */
public object PluginScanPath {
    /** User-local plugin directory: `~/.kuml/plugins/` */
    public val userPluginDir: Path
        get() = Path(System.getProperty("user.home"), ".kuml", "plugins")

    /** System-wide plugin directory from `$KUML_HOME/plugins/` env var, or null if unset */
    public val systemPluginDir: Path?
        get() = System.getenv("KUML_HOME")?.let { Path(it, "plugins") }

    /** All default scan directories (non-null, may not exist yet) */
    public fun defaults(): List<Path> = listOfNotNull(systemPluginDir, userPluginDir)

    /** List all `.jar` files in [dir] (non-recursive, returns empty list if dir doesn't exist) */
    public fun jarsIn(dir: Path): List<File> =
        dir
            .toFile()
            .takeIf { it.isDirectory }
            ?.listFiles { f -> f.extension == "jar" }
            ?.toList()
            ?: emptyList()
}
