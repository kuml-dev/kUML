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
import dev.kuml.desktop.io.AppSettingsStore

fun main() {
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
