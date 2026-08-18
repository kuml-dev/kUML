package dev.kuml.core.script.style

import java.io.File

/**
 * Resolves the directory holding the `:kuml-style-worker` runtime jars
 * (`kuml-style-worker.jar`, `detekt-kotlin-analysis-api*.jar`,
 * `kotlin-compiler.jar`, ...) — the wildcarded `-cp` classpath the
 * style-check child JVM is launched with.
 *
 * Resolution order (first match wins):
 *
 *  1. **`-Dkuml.style.lib=<dir>`** — explicit override. Used by the Gradle
 *     test tasks of `:kuml-cli` / `:kuml-mcp`, which copy the resolved
 *     `:kuml-style-worker` runtime classpath into
 *     `build/style-worker-lib/` and point this property at it (there is no
 *     packaged `lib/style/` next to test classes, since tests run against
 *     `build/classes/...`, not an installed distribution).
 *  2. **`<dir of the jar containing this class>/style`** — the packaged
 *     case: `kuml-cli`/`kuml-mcp`'s `installDist` puts
 *     `kuml-core-script-<version>.jar` in `lib/` and the style worker's jars
 *     in the sibling `lib/style/` (see the `styleWorkerRuntime` distribution
 *     wiring in `kuml-cli/build.gradle.kts` / `kuml-mcp/build.gradle.kts`).
 *  3. **`$KUML_STYLE_LIB`** — environment-variable override, for operators
 *     running a non-standard layout.
 *  4. **not found** → `null`, and the caller degrades to
 *     [StyleCheckResult.Unavailable] rather than failing the whole `validate`
 *     run — a missing style-worker library is an installation defect, not a
 *     defect in the user's script.
 */
internal object StyleWorkerLibLocator {
    internal const val SYSTEM_PROPERTY: String = "kuml.style.lib"
    internal const val ENV_VAR: String = "KUML_STYLE_LIB"

    internal fun resolve(): File? {
        System.getProperty(SYSTEM_PROPERTY)?.let { path ->
            val dir = File(path)
            if (dir.isDirectory) return dir
        }

        jarSiblingStyleDir()?.let { dir ->
            if (dir.isDirectory) return dir
        }

        System.getenv(ENV_VAR)?.let { path ->
            val dir = File(path)
            if (dir.isDirectory) return dir
        }

        return null
    }

    /**
     * `<dir containing the jar this class was loaded from>/style`, or `null`
     * if this class was not loaded from a jar (e.g. running from a Gradle
     * `build/classes/...` directory during a plain unit test, where no
     * packaged `lib/` layout exists at all).
     */
    private fun jarSiblingStyleDir(): File? {
        val location =
            runCatching {
                NamedArgumentStyleCheck::class.java.protectionDomain
                    ?.codeSource
                    ?.location
            }.getOrNull() ?: return null
        val classpathEntry = runCatching { File(location.toURI()) }.getOrNull() ?: return null
        if (!classpathEntry.isFile) return null // not a jar — e.g. a classes/ directory
        val libDir = classpathEntry.parentFile ?: return null
        return File(libDir, "style")
    }
}
