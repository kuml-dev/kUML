package dev.kuml.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.kuml.desktop.about.AboutInfo
import dev.kuml.desktop.about.AboutLink
import dev.kuml.desktop.about.AwtBrowserPort
import dev.kuml.desktop.about.BrowseOutcome
import dev.kuml.desktop.about.BrowserPort
import dev.kuml.desktop.about.LicenseTextLoader
import dev.kuml.desktop.about.openLink
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.mcp.ClipboardWriter
import dev.kuml.desktop.mcp.SystemClipboardWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Help ▸ About: product name, one-line description, copyright, website and license links,
 * version / build date / Java version with a copy button, and the bundled open-source licenses.
 * All texts come from [Strings]; the only reachable addresses are the fixed [AboutLink]s.
 */
@Composable
internal fun AboutDialog(
    strings: Strings,
    onClose: () -> Unit,
    browser: BrowserPort = AwtBrowserPort,
    clipboard: ClipboardWriter = SystemClipboardWriter,
) {
    var copied by remember { mutableStateOf(false) }
    var browseFailed by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    val rows =
        remember(strings) {
            AboutInfo.rows(
                strings = strings,
                version = AppVersion.current,
                buildDate = AppVersion.buildDate,
                javaVersion = AboutInfo.systemJavaDescription(),
            )
        }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    DialogWindow(
        onCloseRequest = onClose,
        title = strings.about.title,
        state = rememberDialogState(width = 480.dp, height = 440.dp),
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.about.productName, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(strings.about.body)
                    Spacer(Modifier.height(8.dp))
                    Text(strings.about.copyright)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        AboutLinkText(
                            link = AboutLink.WEBSITE,
                            label = "kuml.dev",
                            a11y = strings.about.linkWebsiteA11y,
                            browser = browser,
                            onFailed = { browseFailed = true },
                        )
                        AboutLinkText(
                            link = AboutLink.LICENSE,
                            label = AboutInfo.APACHE_LICENSE_NAME,
                            a11y = strings.about.linkLicenseA11y,
                            browser = browser,
                            onFailed = { browseFailed = true },
                        )
                    }
                    if (browseFailed) {
                        Text(strings.about.browserFailed, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                    rows.forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(row.label, modifier = Modifier.width(120.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer { Text(row.value) }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(onClick = {
                            val ok = runCatching { clipboard.write(AboutInfo.clipboardText(rows)) }.isSuccess
                            if (ok) copied = true
                        }) { Text(if (copied) strings.about.copied else strings.about.copyInfo) }
                    }
                    Box(modifier = Modifier.weight(1f))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedButton(onClick = { showLicenses = true }) { Text(strings.about.openLicenses) }
                        Button(onClick = onClose) { Text(strings.dialogClose) }
                    }
                }
            }
        }
    }

    if (showLicenses) LicensesDialog(strings = strings, onClose = { showLicenses = false })
}

@Composable
private fun AboutLinkText(
    link: AboutLink,
    label: String,
    a11y: String,
    browser: BrowserPort,
    onFailed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    if (!browser.isSupported()) {
        // No browser support on this platform: show the address as selectable text, not as a dead link.
        SelectionContainer { Text(link.url) }
        return
    }
    Text(
        text = label,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier =
            Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(role = Role.Button, onClickLabel = a11y) {
                    // Desktop.browse can block on some platforms: keep it off the UI thread.
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) { browser.openLink(link) }
                        if (outcome != BrowseOutcome.OPENED) onFailed()
                    }
                },
    )
}

@Composable
private fun LicensesDialog(
    strings: Strings,
    onClose: () -> Unit,
) {
    // Small bundled resources (size-capped by the loader): reading synchronously once is acceptable.
    val text = remember { LicenseTextLoader().load() }
    DialogWindow(
        onCloseRequest = onClose,
        title = strings.about.licensesTitle,
        state = rememberDialogState(width = 640.dp, height = 520.dp),
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Text(text ?: strings.about.licensesUnavailable, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = onClose) { Text(strings.dialogClose) }
                    }
                }
            }
        }
    }
}
