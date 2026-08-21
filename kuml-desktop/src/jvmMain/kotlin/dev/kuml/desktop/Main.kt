package dev.kuml.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.kuml.ai.vault.ApiKeyVault
import dev.kuml.desktop.io.AppPaths
import dev.kuml.desktop.io.AppSettingsStore
import java.nio.file.Path

fun main() {
    // Logging MUST be configured before the first SLF4J logger is created —
    // ApiKeyVault.detect() below triggers exactly that. See
    // logback-kuml-desktop.xml for why the file is not called logback.xml.
    configureLogging()

    // macOS: Menüleiste im System-Menü-Bar — muss VOR jeder AWT/Swing-Initialisierung gesetzt werden
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.application.name", "kUML Desktop")

    // V3.0.24 — detect OS keychain (may block briefly on first access) before Compose starts
    val vault = ApiKeyVault.detect()

    val store = AppSettingsStore()
    val initial = store.load()
    val appState = AppState(initial)

    application {
        val windowState =
            rememberWindowState(
                width = appState.windowWidth.dp,
                height = appState.windowHeight.dp,
                position =
                    if (appState.windowX < 0) {
                        WindowPosition.PlatformDefault
                    } else {
                        WindowPosition(appState.windowX.dp, appState.windowY.dp)
                    },
            )

        // Fenster-Geometrie in AppState spiegeln
        LaunchedEffect(windowState.size, windowState.position) {
            appState.windowWidth =
                windowState.size.width.value
                    .toInt()
            appState.windowHeight =
                windowState.size.height.value
                    .toInt()
            (windowState.position as? WindowPosition.Absolute)?.let {
                appState.windowX = it.x.value.toInt()
                appState.windowY = it.y.value.toInt()
            }
        }

        val title by derivedStateOf {
            "kUML Desktop" +
                (appState.currentFile?.name?.let { " — $it" } ?: "") +
                (if (appState.isDirty) " •" else "")
        }

        Window(
            onCloseRequest = {
                // Settings synchron speichern und beenden.
                // Vollständiger Dirty-Guard läuft über Quit-Menü-Item in MainWindow.
                store.save(appState.toSettings())
                exitApplication()
            },
            title = title,
            state = windowState,
        ) {
            MainWindow(state = appState, store = store, vault = vault, onQuit = ::exitApplication)
        }
    }
}

/**
 * Selects the desktop-specific Logback configuration and sets its log directory,
 * before any class holding an SLF4J logger is initialised. Visible for testing.
 *
 * The `if` guard lets tests and power users override with an external
 * `-Dlogback.configurationFile=…`. `kuml.desktop.logDir` is always set — even
 * under an external override — so a user-supplied config never finds the
 * property unresolved.
 */
internal fun configureLogging(logDir: Path = AppPaths.logDir()) {
    normalizeInvalidLogLevel(DESKTOP_DEFAULT_LOG_LEVEL)
    System.setProperty("kuml.desktop.logDir", logDir.toString())
    if (System.getProperty("logback.configurationFile") == null) {
        System.setProperty("logback.configurationFile", "logback-kuml-desktop.xml")
    }
}

/**
 * Default root log level for the desktop app, matching
 * `logback-kuml-desktop.xml`'s `${KUML_LOG_LEVEL:-INFO}` substitution. Kept as
 * a named constant so [normalizeInvalidLogLevel]'s fallback can never silently
 * drift out of sync with the XML default.
 */
internal const val DESKTOP_DEFAULT_LOG_LEVEL = "INFO"

/** Level names Logback's `Level.toLevel(String)` actually recognizes. */
private val VALID_KUML_LOG_LEVELS = setOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF", "ALL")

/**
 * Pure decision of what (if anything) [normalizeInvalidLogLevel] should
 * override `KUML_LOG_LEVEL` to. Separated from the actual env/system-property
 * plumbing so the DEBUG-fallback bug this guards against can be unit-tested
 * without needing to mutate real OS environment variables (which the JVM does
 * not allow post-launch).
 *
 * Returns `null` when Logback's own `${KUML_LOG_LEVEL:-default}` substitution
 * should be left alone — either [raw] is unset, or it's already a level name
 * `Level.toLevel(String)` recognizes. Returns [default] when [raw] is set but
 * invalid (a plausible-but-wrong guess like `SILENT`, `NONE`, or `quiet`) —
 * Logback's parser would otherwise silently fall back to DEBUG for such a
 * value: the exact opposite of "quieter" that whoever set it presumably
 * wanted. Duplicated in kuml-cli's and kuml-mcp's Main.kt — same rationale as
 * the duplicated third-party logger pins across the three logback config
 * XMLs, see the comment there.
 */
internal fun normalizedLogLevelOverride(
    raw: String?,
    default: String,
): String? = if (raw != null && raw.trim().uppercase() !in VALID_KUML_LOG_LEVELS) default else null

/**
 * Guards against `Level.toLevel(String)`'s silent fallback to DEBUG for any
 * unrecognized `KUML_LOG_LEVEL` value — see [normalizedLogLevelOverride] for
 * the decision itself.
 *
 * Reads the raw value the same way Logback's own `${KUML_LOG_LEVEL:-default}`
 * substitution would — system property first, OS environment variable as
 * fallback (context property, then system property, then OS environment; see
 * `OptionHelper.propertyLookup`) — so a value set only via `-DKUML_LOG_LEVEL=`
 * (the natural way to set it in a jpackage `.cfg` file's `java-options`, which
 * has no shell to export an env var from) is guarded exactly like one set via
 * the OS environment. Setting a JVM system property of the same name then
 * pre-empts the OS environment variable for Logback's own later lookup, so
 * this must run before the first SLF4J logger is touched, same as
 * `logback.configurationFile` above. Visible for testing.
 */
internal fun normalizeInvalidLogLevel(default: String) {
    val raw = System.getProperty("KUML_LOG_LEVEL") ?: System.getenv("KUML_LOG_LEVEL")
    normalizedLogLevelOverride(raw = raw, default = default)?.let {
        System.setProperty("KUML_LOG_LEVEL", it)
    }
}
