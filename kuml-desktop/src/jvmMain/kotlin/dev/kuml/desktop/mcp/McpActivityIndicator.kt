package dev.kuml.desktop.mcp

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuml.desktop.i18n.Strings
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val READY_COLOR = Color(0xFF228822)
private val FLASH_COLOR = Color(0xFF55E055)
private val BUSY_COLOR = Color(0xFFCC8800)
private val FAILED_COLOR = Color(0xFFCC0000)
private const val FLASH_MILLIS = 400L
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/**
 * Status-bar MCP indicator: green while ready, a short brighter flash whenever a request finishes,
 * a pulsing amber while one is running, red after a failed one. The tooltip lists the last calls.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun McpActivityIndicator(
    port: Int,
    activity: McpActivityTracker,
    strings: Strings,
    onClick: () -> Unit,
) {
    val phase = activity.phase
    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(activity.finishedCounter) {
        if (activity.finishedCounter == 0L) return@LaunchedEffect
        flashing = true
        delay(FLASH_MILLIS)
        flashing = false
    }
    val target =
        when {
            phase == McpActivityPhase.Failed -> FAILED_COLOR
            phase == McpActivityPhase.Busy -> BUSY_COLOR
            flashing -> FLASH_COLOR
            else -> READY_COLOR
        }
    val color by animateColorAsState(targetValue = target, animationSpec = tween(durationMillis = 120))
    val pulse by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 500), RepeatMode.Reverse),
    )
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier.padding(4.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                        text = strings.mcpHint.activityRecentTitle,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontSize = 11.sp,
                    )
                    val calls = activity.recent
                    if (calls.isEmpty()) {
                        Text(strings.mcpHint.activityNoCalls, color = MaterialTheme.colorScheme.inverseOnSurface, fontSize = 11.sp)
                    }
                    calls.forEach { call ->
                        Text(
                            text = describe(call = call, strings = strings),
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        },
    ) {
        Text(
            text = strings.mcpStatusBarIndicator.format(port),
            color = color,
            fontSize = 11.sp,
            modifier =
                Modifier
                    .alpha(if (phase == McpActivityPhase.Busy) pulse else 1f)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 8.dp),
        )
    }
}

private fun describe(
    call: McpCall,
    strings: Strings,
): String {
    val time = TIME_FORMAT.format(Instant.ofEpochMilli(call.startedAtMillis))
    val state =
        when (call.ok) {
            null -> strings.mcpHint.activityBusy
            true -> "ok"
            false -> strings.mcpHint.activityFailed
        }
    return "${call.label}, $time, $state"
}
