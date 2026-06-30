package dev.kuml.jetbrains

import java.io.File

/**
 * Thin rendering facade used by [KumlPreviewPanel].
 *
 * ## Why the CLI (and not in-process scripting)
 *
 * kUML scripts are compiled + evaluated by the Kotlin scripting host
 * (`BasicJvmScriptingHost`). That host is **not reachable** from the IntelliJ
 * plugin classloader — the bundled IDE Kotlin plugin exposes only the scripting
 * *definitions* API for editor support, not the JVM scripting *host*. On top of
 * that, kUML targets Kotlin scripting 2.4.x while the bundled IDE Kotlin plugin
 * is 2.1.x, so even bundling the host would risk version conflicts.
 *
 * Therefore the preview renders by shelling out to the **external `kuml` CLI**
 * (a self-contained process with its own consistent classpath), exactly like the
 * Obsidian kUML plugin. [KumlCliLocator] finds the binary; [KumlCliRenderer]
 * runs it on the current (possibly unsaved) editor text and returns the SVG.
 */
internal object KumlPreviewRenderer {
    /**
     * Outcome of a preview render attempt.
     *
     * Carrying an explicit [Failure] (instead of collapsing to `null`) lets the
     * panel surface *why* nothing rendered — a missing CLI, a script error, or a
     * render exception — rather than silently showing "No diagram".
     */
    sealed interface Outcome {
        /** Successful render → ready-to-parse SVG string. */
        data class Svg(
            val svg: String,
        ) : Outcome

        /** Script rendered but produced no diagram (empty output). */
        data object Empty : Outcome

        /** Something went wrong — [message] is shown in the preview pane. */
        data class Failure(
            val message: String,
        ) : Outcome
    }

    /**
     * Render [scriptText] to an SVG string, or `null` if rendering did not
     * succeed. Thin wrapper over [renderOutcome].
     *
     * @param scriptText Full text of the `.kuml.kts` file.
     * @param scriptName Path (or name) of the source file — used to name temp
     *   files and to locate a local CLI build by walking up the directory tree.
     */
    fun render(
        scriptText: String,
        scriptName: String,
        theme: String = KumlPreviewSettings.DEFAULT_THEME,
    ): String? = (renderOutcome(scriptText, scriptName, theme) as? Outcome.Svg)?.svg

    /**
     * Render [scriptText], returning a detailed [Outcome].
     */
    fun renderOutcome(
        scriptText: String,
        scriptName: String,
        theme: String = KumlPreviewSettings.DEFAULT_THEME,
    ): Outcome =
        try {
            val hintDir = File(scriptName).absoluteFile.parentFile
            val binary =
                KumlCliLocator.resolve(hintDir)
                    ?: return Outcome.Failure(cliNotFoundMessage())
            KumlCliRenderer.render(binary, scriptText, scriptName, theme)
        } catch (t: Throwable) {
            Outcome.Failure("Render-Ausnahme: ${t::class.java.name}: ${t.message ?: "(keine Meldung)"}")
        }

    private fun cliNotFoundMessage(): String =
        buildString {
            appendLine("kUML-CLI nicht gefunden.")
            appendLine()
            appendLine("Die Vorschau rendert über die externe 'kuml'-CLI. Bitte einen der folgenden Wege wählen:")
            appendLine(" • 'kuml' in den PATH legen (z. B. via Homebrew-Tap kuml-dev/homebrew-kuml), oder")
            appendLine(" • den CLI-Pfad in Settings → Tools → kUML Preview eintragen, oder")
            appendLine(" • die Umgebungsvariable KUML_CLI bzw. -Dkuml.cli.path=… setzen.")
            appendLine()
            appendLine("Lokaler Gradle-Build: ./gradlew :kuml-cli:installDist erzeugt")
            append("   kuml-cli/build/install/kuml/bin/kuml")
        }
}
