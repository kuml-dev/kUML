package dev.kuml.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() {
    // macOS: Menüleiste im System-Menü-Bar — muss VOR jeder AWT/Swing-Initialisierung gesetzt werden
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.application.name", "kUML Desktop")

    val appState = AppState()

    application {
        val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)
        Window(
            onCloseRequest = ::exitApplication,
            title = "kUML Desktop",
            state = windowState,
        ) {
            MainWindow(state = appState)
        }
    }
}
