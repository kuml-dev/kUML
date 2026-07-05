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
    /**
     * User-local plugin directory: `~/.kuml/plugins/` (or the test override set via
     * [overrideUserPluginDirForTest], if any).
     *
     * Without a test seam, every test that exercises `kuml plugin install`/`upgrade`/
     * `remove` writes and deletes real files directly in the developer's actual
     * `~/.kuml/plugins/` — on a machine where the test suite is run repeatedly (e.g.
     * during local development), leftover fixture JARs from one run silently
     * contaminate later, unrelated test runs (PluginLoader.load() picks them up and
     * fails with ClassNotFoundException for classes that only ever existed as
     * in-memory test fixtures). Mirrors the same pattern already used by
     * `dev.kuml.ai.tools.io.KumlHome.overrideBaseForTest`.
     */
    public val userPluginDir: Path
        get() {
            val override = System.getProperty("kuml.plugins.dir")
            return if (override != null) {
                Path(override)
            } else {
                Path(System.getProperty("user.home"), ".kuml", "plugins")
            }
        }

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

    /**
     * Test seam: redirects [userPluginDir] to [dir] for the current JVM.
     *
     * Set in a `beforeSpec`/`beforeEach` block and clear via [clearTestOverride] in the
     * matching `afterSpec`/`afterEach` to avoid polluting the real ~/.kuml/plugins
     * directory during tests.
     */
    public fun overrideUserPluginDirForTest(dir: Path) {
        System.setProperty("kuml.plugins.dir", dir.toString())
    }

    /** Clears any test override set via [overrideUserPluginDirForTest]. */
    public fun clearTestOverride() {
        System.clearProperty("kuml.plugins.dir")
    }
}
