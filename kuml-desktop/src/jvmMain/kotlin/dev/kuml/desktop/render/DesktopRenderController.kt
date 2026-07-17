package dev.kuml.desktop.render

import dev.kuml.desktop.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DesktopRenderController(
    private val state: AppState,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 300L,
) {
    private var debounceJob: Job? = null

    fun scheduleRender(script: String) {
        debounceJob?.cancel()
        debounceJob =
            scope.launch {
                delay(debounceMs)
                state.isRendering = true
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            DesktopRenderPipeline.render(script, state.theme)
                        }
                    when (result) {
                        is DesktopRenderResult.Svg -> {
                            state.lastSvg = result.svg
                            state.lastError = null
                        }
                        is DesktopRenderResult.Error -> {
                            state.lastError = result.message
                        }
                    }
                } catch (e: Exception) {
                    // CancellationException NIEMALS verschlucken — bricht Structured Concurrency
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    state.lastError = "Unerwarteter Fehler: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    state.isRendering = false
                }
            }
    }

    fun cancel() {
        debounceJob?.cancel()
    }
}
