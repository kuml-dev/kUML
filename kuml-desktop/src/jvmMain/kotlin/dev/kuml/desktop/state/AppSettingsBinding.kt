package dev.kuml.desktop.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.kuml.desktop.AppState
import dev.kuml.desktop.io.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun rememberAppSettingsBinding(state: AppState, store: AppSettingsStore) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var saveJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(
        state.theme, state.language,
        state.recentFiles.toList(),
        state.lastDir,
        state.windowWidth, state.windowHeight, state.windowX, state.windowY,
    ) {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            withContext(Dispatchers.IO) {
                store.save(state.toSettings())
            }
        }
    }
}
