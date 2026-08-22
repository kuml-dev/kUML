package dev.kuml.desktop.ai.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuml.desktop.i18n.Strings

/**
 * Reine Entscheidungsfunktion für die Enter-Taste -- extrahiert, weil kuml-desktop keine
 * Compose-UI-Test-Infrastruktur hat (nur Kotest-Unit-Tests, siehe PreviewPaneTest/AppStateTest).
 *
 * BEKANNTE EINSCHRÄNKUNG (IME/CJK): Bei aktiver Eingabemethoden-Komposition bestätigt Enter
 * üblicherweise die Komposition, statt zu senden. Compose Multiplatform 1.11.1 exponiert an der
 * öffentlichen TextField-API kein isComposing-Flag, mit dem sich der Fall unterscheiden ließe.
 * Nicht gelöst, bewusst dokumentiert.
 */
internal fun shouldSubmitOnKey(
    type: KeyEventType,
    key: Key,
    isShiftPressed: Boolean,
    isRunning: Boolean,
): Boolean =
    type == KeyEventType.KeyDown &&
        (key == Key.Enter || key == Key.NumPadEnter) &&
        !isShiftPressed &&
        !isRunning

/**
 * True, wenn das Event konsumiert werden muss (auch KeyUp), damit kein Zeilenumbruch entsteht.
 * Ein unmodifiziertes Enter wird IMMER konsumiert -- sowohl KeyDown (Submit-Fall) als auch KeyUp
 * (verhindert einen zusätzlichen typed-Event-Pfad, der sonst trotzdem einen Umbruch einfügen
 * könnte). Shift+Enter wird NIE konsumiert -- das ist der gewünschte Zeilenumbruch.
 */
internal fun shouldConsumeKey(
    type: KeyEventType,
    key: Key,
    isShiftPressed: Boolean,
    isRunning: Boolean,
): Boolean =
    (key == Key.Enter || key == Key.NumPadEnter) &&
        !isShiftPressed &&
        (type == KeyEventType.KeyDown || type == KeyEventType.KeyUp)

@Composable
fun InputPane(
    isRunning: Boolean,
    strings: Strings,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }

    fun submit() {
        if (text.isNotBlank() && !isRunning) {
            onSend(text)
            text = ""
        }
    }

    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier =
                    Modifier.weight(1f).onPreviewKeyEvent { event: KeyEvent ->
                        if (
                            shouldSubmitOnKey(
                                type = event.type,
                                key = event.key,
                                isShiftPressed = event.isShiftPressed,
                                isRunning = isRunning,
                            )
                        ) {
                            submit()
                            true
                        } else {
                            shouldConsumeKey(
                                type = event.type,
                                key = event.key,
                                isShiftPressed = event.isShiftPressed,
                                isRunning = isRunning,
                            )
                        }
                    },
                minLines = 1,
                maxLines = 6,
                placeholder = { Text(strings.aiInputPlaceholder) },
            )
            Spacer(Modifier.width(8.dp))
            if (isRunning) {
                IconButton(onClick = onStop) { Text("⏹") }
            } else {
                IconButton(onClick = ::submit) { Text("▶") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = strings.aiInputHint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
