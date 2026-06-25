package dev.kuml.jetbrains

import java.io.File

/**
 * Locates the `kuml` command-line binary used by the live preview.
 *
 * The plugin renders previews by shelling out to the kUML CLI (an external,
 * self-contained process) instead of running the Kotlin scripting host
 * in-process. The scripting host (`BasicJvmScriptingHost`) is **not** reachable
 * from the IDE plugin classloader, and kUML's scripting version (2.4.x) differs
 * from the bundled IDE Kotlin plugin (2.1.x) — so in-process evaluation is not
 * viable. The CLI sidesteps all of this with its own consistent classpath.
 *
 * Resolution order (first match wins):
 *  1. Explicit override — system property `kuml.cli.path` or env `KUML_CLI`.
 *  2. A user-configured path persisted via [KumlPreviewSettings].
 *  3. `kuml` on the `PATH` (`which` / `where`).
 *  4. Common install locations (Homebrew, `~/.local/bin`).
 *  5. A local Gradle build, discovered by walking up from [searchHintDir] to a
 *     `kuml-cli/build/install/kuml/bin/kuml` distribution (handy when editing
 *     inside the kUML repo itself).
 */
internal object KumlCliLocator {
    /** Relative path of the CLI launcher inside a Gradle `installDist` output. */
    private val INSTALL_REL =
        listOf("kuml-cli", "build", "install", "kuml", "bin")

    fun resolve(searchHintDir: File?): File? {
        explicitOverride()?.let { return it }
        configuredPath()?.let { return it }
        onPath()?.let { return it }
        commonLocations()?.let { return it }
        return walkUpForLocalBuild(searchHintDir)
    }

    private fun explicitOverride(): File? =
        sequenceOf(System.getProperty("kuml.cli.path"), System.getenv("KUML_CLI"))
            .filterNotNull()
            .map(::File)
            .firstOrNull(::isUsable)

    private fun configuredPath(): File? =
        KumlPreviewSettings
            .cliPath()
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf(::isUsable)

    private fun onPath(): File? =
        try {
            val tool = if (isWindows()) "where" else "which"
            val proc =
                ProcessBuilder(tool, launcherName())
                    .redirectErrorStream(true)
                    .start()
            val out =
                proc.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            proc.waitFor()
            out
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map(::File)
                .firstOrNull(::isUsable)
        } catch (_: Throwable) {
            null
        }

    private fun commonLocations(): File? {
        val home = System.getProperty("user.home") ?: ""
        return listOf(
            "/opt/homebrew/bin/${launcherName()}",
            "/usr/local/bin/${launcherName()}",
            "$home/.local/bin/${launcherName()}",
        ).map(::File).firstOrNull(::isUsable)
    }

    private fun walkUpForLocalBuild(start: File?): File? {
        var dir: File? = start?.absoluteFile
        var depth = 0
        while (dir != null && depth < 40) {
            val candidate =
                INSTALL_REL
                    .fold(dir) { acc, seg -> File(acc, seg) }
                    .let { File(it, launcherName()) }
            if (isUsable(candidate)) return candidate
            dir = dir.parentFile
            depth++
        }
        return null
    }

    private fun launcherName(): String = if (isWindows()) "kuml.bat" else "kuml"

    private fun isWindows(): Boolean =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("win")

    private fun isUsable(f: File): Boolean = f.isFile && f.canExecute()
}
