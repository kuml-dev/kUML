package dev.kuml.desktop.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.ui.ButtonAction
import dev.kuml.desktop.ui.IconLabel

/** Banner colors per tone; the amber pair is the only place with fixed hex colors. */
internal object StatusColors {
    private val amberLightBg = Color(0xFFFFF4E0)
    private val amberLightFg = Color(0xFF7A4A00)
    private val amberDarkBg = Color(0xFF3D2A00)
    private val amberDarkFg = Color(0xFFFFD699)

    /** Background and content color of [tone] for the current theme. */
    @Composable
    fun of(tone: StatusTone): Pair<Color, Color> {
        val scheme = MaterialTheme.colorScheme
        val dark = scheme.background.luminance() < 0.5f
        return when (tone) {
            StatusTone.NEUTRAL -> scheme.surfaceVariant to scheme.onSurfaceVariant
            StatusTone.WARNING -> if (dark) amberDarkBg to amberDarkFg else amberLightBg to amberLightFg
            StatusTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
            StatusTone.SUCCESS -> scheme.primaryContainer to scheme.onPrimaryContainer
        }
    }
}

/**
 * Banner with a headline, optional detail lines and optional actions. The text scrolls when it
 * does not fit; the actions stay outside the scrolling part so they are always reachable.
 */
@Composable
internal fun StatusBanner(
    tone: StatusTone,
    headline: String,
    details: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val (background, content) = StatusColors.of(tone)
    Surface(shape = RoundedCornerShape(8.dp), color = background, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(headline, style = MaterialTheme.typography.titleSmall, color = content)
                details.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = content) }
            }
            if (actions != null) {
                Row(modifier = Modifier.padding(top = 8.dp)) { actions() }
            }
        }
    }
}

/** Presentation of a [PdfStatusView]; contains no decisions, only strings and callbacks. */
@Composable
internal fun PdfStatusArea(
    view: PdfStatusView,
    strings: Strings,
    onUseA3: () -> Unit,
    onExportAnyway: () -> Unit,
    onOverwrite: () -> Unit,
    onCancelOverwrite: () -> Unit,
) {
    val s = strings.pdfStatus
    when (view) {
        PdfStatusView.Idle ->
            Text(s.idleHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is PdfStatusView.Checking ->
            Column {
                Text(strings.pdfProgress.format(view.done, view.total), style = MaterialTheme.typography.bodySmall)
                if (view.total > 0) {
                    LinearProgressIndicator(progress = { view.done / view.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        is PdfStatusView.Warning -> {
            val headline =
                if (view.failureCount > 0) s.warnHeadFailures.format(view.failureCount) else s.warnHeadScale.format(view.scaleCount)
            val details =
                buildList {
                    if (view.failureCount > 0) add(strings.pdfDiagramFailures.format(view.failureCount, view.failureSample.joinToString()))
                    view.layoutNote?.let { add(strings.pdfLayoutSummary.format(it.a3Diagrams, it.tiledDiagrams, it.tilePages)) }
                    if (view.scaleCount > 0) add(strings.pdfScaleWarning.format(view.scaleCount, view.scaleSample.joinToString()))
                    if (view.hiddenCount > 0) add(s.andMore.format(view.hiddenCount))
                    if (view.hintMinScale) add(s.minScaleHint)
                }
            StatusBanner(tone = view.tone(), headline = headline, details = details) {
                if (view.offerA3Landscape) {
                    Button(onClick = onUseA3) { IconLabel(action = ButtonAction.USE_A3, text = s.useA3Landscape) }
                }
                TextButton(onClick = onExportAnyway) { IconLabel(action = ButtonAction.PROCEED_RISKY, text = strings.pdfExportAnyway) }
            }
        }
        is PdfStatusView.Overwrite ->
            StatusBanner(
                tone = view.tone(),
                headline =
                    if (view.count == 0) {
                        strings.pdfOverwriteConfirm
                    } else {
                        strings.pdfOverwriteDirConfirm.format(view.count, view.names.joinToString())
                    },
            ) {
                Button(onClick = onOverwrite) { IconLabel(action = ButtonAction.PROCEED_RISKY, text = s.overwriteButton) }
                TextButton(onClick = onCancelOverwrite) { IconLabel(action = ButtonAction.CANCEL, text = strings.pdfCancel) }
            }
        is PdfStatusView.Done -> StatusBanner(tone = view.tone(), headline = strings.pdfExportDone.format(view.path))
        is PdfStatusView.Failed ->
            StatusBanner(
                tone = view.tone(),
                headline = view.message?.let { strings.pdfExportFailed.format(it) } ?: strings.pdfBusy,
            )
    }
}
