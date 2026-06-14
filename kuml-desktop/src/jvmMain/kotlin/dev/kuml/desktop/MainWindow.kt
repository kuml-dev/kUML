package dev.kuml.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import dev.kuml.desktop.editor.EditorPane
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.preview.PreviewPane
import dev.kuml.desktop.render.DesktopRenderController

/**
 * Top-Level-Composable für das kUML Desktop Hauptfenster.
 * Deklariert die native [MenuBar] und legt [EditorPane] + [PreviewPane] nebeneinander.
 */
@Composable
fun FrameWindowScope.MainWindow(state: AppState) {
    val strings = Strings.forLanguage(state.language)
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { DesktopRenderController(state, scope) }

    DisposableEffect(controller) {
        onDispose { controller.cancel() }
    }

    MenuBar {
        Menu(strings.menuFile) {
            Item(strings.menuFileNew, onClick = { state.script = "" })
            Item(strings.menuFileOpen, onClick = { /* V3.0.12: JFileChooser */ })
            Item(strings.menuFileSave, onClick = { /* V3.0.12 */ })
            Item(strings.menuFileSaveAs, onClick = { /* V3.0.12 */ })
            Separator()
            Item(strings.menuFileQuit, onClick = { /* exitApplication() via lambda V3.0.12 */ })
        }
        Menu(strings.menuEdit) {
            Item(strings.menuEditUndo, onClick = { /* V3.0.11 */ })
            Item(strings.menuEditRedo, onClick = { /* V3.0.11 */ })
            Separator()
            Item(strings.menuEditFind, onClick = { /* V3.0.11 */ })
        }
        Menu(strings.menuView) {
            Menu(strings.menuViewTheme) {
                Item("Plain", onClick = { state.theme = "plain" })
                Item("Dark", onClick = { state.theme = "dark" })
                Item("Blueprint", onClick = { state.theme = "blueprint" })
            }
            Menu(strings.menuViewLanguage) {
                Item("Deutsch", onClick = { state.language = "de" })
                Item("English", onClick = { state.language = "en" })
            }
        }
        Menu(strings.menuHelp) {
            Item(strings.menuHelpDocs, onClick = { /* open https://kuml.dev/docs */ })
            Item(strings.menuHelpAbout, onClick = { /* V3.0.12: About-Dialog */ })
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Hauptbereich: Editor | Vorschau
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    EditorPane(
                        state = state,
                        controller = controller,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
                    PreviewPane(
                        state = state,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                // StatusBar — reines Compose, KEIN SwingPanel → immer sichtbar
                StatusBar(state = state)
            }
        }
    }
}

/**
 * Statusleiste am unteren Rand.
 *
 * Zeigt Fehler (rot) oder Render-Status (grau). Liegt AUSSERHALB jedes SwingPanel,
 * sodass sie nie von AWT-Heavyweight-Komponenten verdeckt wird.
 */
@Composable
private fun StatusBar(state: AppState) {
    val strings = Strings.forLanguage(state.language)
    val (text, color) = when {
        state.lastError != null -> state.lastError!! to Color(0xFFCC0000)
        state.isRendering      -> strings.statusRendering to Color.Gray
        state.lastSvg.isNotBlank() -> strings.statusReady to Color(0xFF228822)
        else                   -> strings.statusNoDiagram to Color.Gray
    }
    HorizontalDivider()
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
