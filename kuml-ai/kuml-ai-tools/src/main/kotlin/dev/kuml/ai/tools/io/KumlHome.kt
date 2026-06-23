package dev.kuml.ai.tools.io

import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the ~/.kuml base directory and well-known sub-directories.
 *
 * The base directory honors the `kuml.home.dir` system property first (test seam),
 * then falls back to `~/.kuml`. All resolved directories are created on demand.
 */
public object KumlHome {
    /** Returns the ~/.kuml base directory (or the test override). */
    public fun base(): Path {
        val override = System.getProperty("kuml.home.dir")
        return if (override != null) {
            Path.of(override)
        } else {
            Path.of(System.getProperty("user.home")).resolve(".kuml")
        }
    }

    /** Returns the audit log directory (~/.kuml/audit), creating it if absent. */
    public fun auditDir(): Path = ensure(base().resolve("audit"))

    /** Returns the patch DB directory (~/.kuml/patches), creating it if absent. */
    public fun patchesDir(): Path = ensure(base().resolve("patches"))

    private fun ensure(p: Path): Path {
        Files.createDirectories(p)
        return p
    }

    /**
     * Test seam: redirects all KumlHome resolution to [dir] for the current JVM.
     *
     * Set `kuml.home.dir` in a `beforeSpec` block and clear it in `afterSpec`
     * (restore to `null` via `System.clearProperty`) to avoid polluting the real
     * ~/.kuml directory during tests.
     */
    public fun overrideBaseForTest(dir: Path) {
        System.setProperty("kuml.home.dir", dir.toString())
    }

    /** Clears any test override set via [overrideBaseForTest]. */
    public fun clearTestOverride() {
        System.clearProperty("kuml.home.dir")
    }
}
