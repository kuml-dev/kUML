package dev.kuml.langsupport.cli

import java.io.File

/**
 * Locates the `kuml` (and, for the Wave 2 LSP consumer, `kuml-lsp`) command-line
 * binaries used by editor integrations.
 *
 * Editor integrations (IntelliJ live preview, the LSP server, ...) render/validate
 * by shelling out to the external kUML CLI (a self-contained process) instead of
 * running the Kotlin scripting host in-process. The scripting host
 * (`BasicJvmScriptingHost`) is often **not** reachable from an embedding
 * classloader (e.g. the IntelliJ plugin classloader), and kUML's scripting
 * version can differ from a bundled Kotlin plugin/runtime — so in-process
 * evaluation is not always viable. The CLI sidesteps all of this with its own
 * consistent classpath.
 *
 * Resolution order (first match wins):
 *  1. Explicit override — system property / env var (`kuml.cli.path` / `KUML_CLI`
 *     for [resolve], `kuml.lsp.path` / `KUML_LSP` for [resolveLsp]).
 *  2. A caller-supplied `configuredPath` (e.g. persisted via IDE settings).
 *  3. The launcher on the `PATH` (`which` / `where`).
 *  4. Common install locations (Homebrew, `~/.local/bin`).
 *  5. A local Gradle build, discovered by walking up from `searchHintDir` to a
 *     `.../build/install/<name>/bin/<launcher>` distribution (handy when editing
 *     inside the kUML repo itself).
 */
public object KumlCliLocator {
    /** Relative path of the CLI launcher inside a Gradle `installDist` output. */
    private val CLI_INSTALL_REL = listOf("kuml-cli", "build", "install", "kuml", "bin")

    /** Relative path of the LSP launcher inside a Gradle `installDist` output (Wave 2). */
    private val LSP_INSTALL_REL = listOf("kuml-language-server", "build", "install", "kuml-lsp", "bin")

    /**
     * Resolve the `kuml` CLI launcher.
     *
     * @param configuredPath an optional user-set override (e.g. the IntelliJ
     *   plugin passes its persisted `KumlPreviewSettings.cliPath()`); `null` for none.
     */
    public fun resolve(
        searchHintDir: File?,
        configuredPath: String? = null,
    ): File? =
        resolveLauncher(
            searchHintDir = searchHintDir,
            configuredPath = configuredPath,
            launcher = cliLauncherName(),
            installRel = CLI_INSTALL_REL,
            envVar = "KUML_CLI",
            sysProp = "kuml.cli.path",
        )

    /**
     * Resolve the `kuml-lsp` launcher (Wave 2 consumer). Same resolution order
     * as [resolve]; the walk-up step targets `kuml-language-server/build/install/kuml-lsp/bin`.
     */
    public fun resolveLsp(
        searchHintDir: File?,
        configuredPath: String? = null,
    ): File? =
        resolveLauncher(
            searchHintDir = searchHintDir,
            configuredPath = configuredPath,
            launcher = lspLauncherName(),
            installRel = LSP_INSTALL_REL,
            envVar = "KUML_LSP",
            sysProp = "kuml.lsp.path",
        )

    private fun resolveLauncher(
        searchHintDir: File?,
        configuredPath: String?,
        launcher: String,
        installRel: List<String>,
        envVar: String,
        sysProp: String,
    ): File? {
        explicitOverride(sysProp, envVar)?.let { return it }
        configuredPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf(::isUsable)
            ?.let { return it }
        onPath(launcher)?.let { return it }
        commonLocations(launcher)?.let { return it }
        return walkUpForLocalBuild(searchHintDir, launcher, installRel)
    }

    private fun explicitOverride(
        sysProp: String,
        envVar: String,
    ): File? =
        sequenceOf(System.getProperty(sysProp), System.getenv(envVar))
            .filterNotNull()
            .map(::File)
            .firstOrNull(::isUsable)

    private fun onPath(launcher: String): File? =
        try {
            val tool = if (isWindows()) "where" else "which"
            val proc =
                ProcessBuilder(tool, launcher)
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

    private fun commonLocations(launcher: String): File? {
        val home = System.getProperty("user.home") ?: ""
        return listOf(
            "/opt/homebrew/bin/$launcher",
            "/usr/local/bin/$launcher",
            "$home/.local/bin/$launcher",
        ).map(::File).firstOrNull(::isUsable)
    }

    private fun walkUpForLocalBuild(
        start: File?,
        launcher: String,
        installRel: List<String>,
    ): File? {
        var dir: File? = start?.absoluteFile
        var depth = 0
        while (dir != null && depth < 40) {
            val candidate =
                installRel
                    .fold(dir) { acc, seg -> File(acc, seg) }
                    .let { File(it, launcher) }
            if (isUsable(candidate)) return candidate
            dir = dir.parentFile
            depth++
        }
        return null
    }

    private fun cliLauncherName(): String = if (isWindows()) "kuml.bat" else "kuml"

    private fun lspLauncherName(): String = if (isWindows()) "kuml-lsp.bat" else "kuml-lsp"

    private fun isWindows(): Boolean =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("win")

    private fun isUsable(f: File): Boolean = f.isFile && f.canExecute()
}
