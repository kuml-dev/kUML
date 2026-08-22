package dev.kuml.desktop.render

import dev.kuml.desktop.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * All inputs a render actually depends on -- grows HERE when a new input starts affecting the
 * rendered image, rather than as a new [DesktopRenderController.scheduleRender] call site
 * scattered somewhere else in the composable tree (V3.7.4, design review P6: before this, only
 * `state.script` changes triggered a render at all -- a theme or watermark change silently did
 * nothing until the next keystroke, and in [dev.kuml.desktop.AppState.ViewMode.DIAGRAM] mode the
 * editor that owned the only trigger wasn't even composed, so nothing re-rendered ever).
 */
data class RenderInputs(
    val script: String,
    val themeName: String,
    val watermark: Boolean,
)

class DesktopRenderController(
    private val state: AppState,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    private var debounceJob: Job? = null

    /**
     * @param delayMs pass `0` for a menu/toggle-driven trigger (theme, watermark, view mode) --
     * the debounce below exists only to avoid re-rendering on every keystroke.
     */
    fun scheduleRender(
        inputs: RenderInputs,
        delayMs: Long = debounceMs,
    ) {
        debounceJob?.cancel()
        debounceJob =
            scope.launch {
                if (delayMs > 0) delay(delayMs)
                state.isRendering = true
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            // Uses the values captured in [inputs], NOT a fresh read of [state] --
                            // state could have changed again between scheduling and this coroutine
                            // actually running, and a render must reflect one consistent snapshot,
                            // never a torn mix of old script + new theme (or vice versa).
                            DesktopRenderPipeline.render(
                                script = inputs.script,
                                themeName = inputs.themeName,
                                watermark = inputs.watermark,
                            )
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

    /** Compatibility overload for existing tests/callers that only vary [state]'s script. */
    fun scheduleRender(
        script: String,
        delayMs: Long = debounceMs,
    ) = scheduleRender(
        inputs = RenderInputs(script = script, themeName = state.theme, watermark = state.showWatermark),
        delayMs = delayMs,
    )

    fun cancel() {
        debounceJob?.cancel()
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 300L
    }
}
