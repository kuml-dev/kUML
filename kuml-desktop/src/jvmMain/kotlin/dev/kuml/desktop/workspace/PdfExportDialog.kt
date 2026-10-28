package dev.kuml.desktop.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kuml.cli.workspace.WorkspacePdfExport
import dev.kuml.desktop.i18n.Strings
import dev.kuml.workspace.OkfFinding
import dev.kuml.workspace.WorkspaceScanner
import dev.kuml.workspace.pdf.ExportPhase
import dev.kuml.workspace.pdf.PageFormat
import dev.kuml.workspace.pdf.PageOrientation
import dev.kuml.workspace.pdf.PdfExportException
import dev.kuml.workspace.pdf.PreparedExport
import dev.kuml.workspace.pdf.ScaleWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Where the dialog is in its check-then-export flow. */
private sealed interface PdfUiState {
    data object Idle : PdfUiState

    data class Preparing(
        val done: Int,
        val total: Int,
    ) : PdfUiState

    data class Warnings(
        val warnings: List<ScaleWarning>,
        val failures: List<OkfFinding> = emptyList(),
    ) : PdfUiState

    /** [existing] lists files of a directory target that would be replaced; empty for a single-file target. */
    data class ConfirmOverwrite(
        val target: File,
        val existing: List<String> = emptyList(),
    ) : PdfUiState

    data class Done(
        val path: String,
    ) : PdfUiState

    data class Failed(
        val message: String,
    ) : PdfUiState

    data object Busy : PdfUiState
}

/**
 * "Export workspace as PDF…" dialog. Three presets on top, advanced options folded away; options that do not apply
 * are shown disabled with the reason. "Check and export" renders and lays out first (cancellable, with progress), a
 * too-small diagram yields a non-modal bar with "use A3 landscape" / "export anyway", and only then a save dialog opens.
 * Rendering holds the shared evaluation gate, so it never runs concurrently with an MCP render.
 */
@Composable
fun PdfExportDialog(
    strings: Strings,
    workspaceRoot: File,
    trusted: Boolean,
    producerVersion: String,
    evalGate: Semaphore,
    initialDir: File?,
    chooseSaveFile: (suggestedName: String, initialDir: File?) -> File?,
    chooseSaveDirectory: (initialDir: File?) -> File?,
    onDismiss: () -> Unit,
) {
    var form by remember { mutableStateOf(PdfExportFormState()) }
    var ui by remember { mutableStateOf<PdfUiState>(PdfUiState.Idle) }
    var prepared by remember { mutableStateOf<PreparedExport?>(null) }
    val cancelled = remember { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { cancelled.set(true) } }

    val busyWorking = ui is PdfUiState.Preparing

    fun closePrepared() {
        prepared?.close()
        prepared = null
    }

    fun write(
        target: File,
        overwrite: Boolean,
    ) {
        val p = prepared ?: return
        scope.launch {
            val outcome =
                withContext(Dispatchers.IO) {
                    runCatching { WorkspacePdfExport.write(prepared = p, target = target, overwrite = overwrite) }
                }
            prepared = null
            outcome
                .onSuccess {
                    val first = it.files.firstOrNull()
                    ui =
                        PdfUiState.Done(
                            path = (if (p.outputCount > 1) first?.parentFile?.path else null) ?: first?.path ?: target.path,
                        )
                }.onFailure { e ->
                    ui = PdfUiState.Failed(message = if (e is PdfExportException) e.message.orEmpty() else e.javaClass.simpleName)
                }
        }
    }

    fun chooseAndWrite() {
        val p = prepared ?: return
        val asDir = form.preset == PdfPreset.PER_DOCUMENT || (form.preset == PdfPreset.DIAGRAMS && form.split)
        val target =
            if (asDir) {
                chooseSaveDirectory(initialDir)
            } else {
                chooseSaveFile("${workspaceRoot.name}${if (form.preset == PdfPreset.DIAGRAMS) "-diagrams" else ""}.pdf", initialDir)
            }
        if (target == null) {
            ui = PdfUiState.Idle
            closePrepared()
            return
        }
        if (!asDir &&
            target.exists() &&
            p.outputCount == 1
        ) {
            ui = PdfUiState.ConfirmOverwrite(target = target)
        } else if (asDir) {
            val existing = p.outputFileNames.filter { File(target, it).exists() }
            if (existing.isEmpty()) {
                write(target = target, overwrite = false)
            } else {
                ui =
                    PdfUiState.ConfirmOverwrite(target = target, existing = existing)
            }
        } else {
            write(target = target, overwrite = false)
        }
    }

    fun check() {
        closePrepared()
        cancelled.set(false)
        ui = PdfUiState.Preparing(done = 0, total = 0)
        val options = form.toOptions(producerVersion = producerVersion)
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    if (!evalGate.tryAcquire(2, TimeUnit.SECONDS)) {
                        return@withContext null
                    }
                    try {
                        runCatching {
                            val ws = WorkspaceScanner.scan(root = workspaceRoot)
                            WorkspacePdfExport.prepare(
                                workspace = ws,
                                options = options,
                                progress = { done, total, phase ->
                                    if (phase ==
                                        ExportPhase.RENDERING
                                    ) {
                                        ui = PdfUiState.Preparing(done = done, total = total)
                                    }
                                },
                                cancelled = { cancelled.get() },
                            )
                        }
                    } finally {
                        evalGate.release()
                    }
                }
            when {
                result == null -> ui = PdfUiState.Busy
                result.isFailure -> {
                    val e = result.exceptionOrNull()
                    ui =
                        if (e is PdfExportException &&
                            e.code == PdfExportException.Code.CANCELLED
                        ) {
                            PdfUiState.Idle
                        } else {
                            PdfUiState.Failed(
                                message = if (e is PdfExportException) e.message.orEmpty() else e?.javaClass?.simpleName.orEmpty(),
                            )
                        }
                }
                else -> {
                    val p = result.getOrThrow()
                    prepared = p
                    if (p.warnings.isNotEmpty() || p.failures.isNotEmpty()) {
                        ui = PdfUiState.Warnings(warnings = p.warnings, failures = p.failures)
                    } else {
                        chooseAndWrite()
                    }
                }
            }
        }
    }

    WindowAlertDialog(
        title = strings.pdfExportTitle,
        onDismissRequest = {
            cancelled.set(true)
            closePrepared()
            onDismiss()
        },
        width = 680.dp,
        height = 640.dp,
        content = {
            Column {
                if (!trusted) {
                    Text(strings.pdfUntrustedReason, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                }
                PresetRow(label = strings.pdfPresetHandbook, selected = form.preset == PdfPreset.HANDBOOK, enabled = !busyWorking) {
                    form =
                        form.withPreset(PdfPreset.HANDBOOK)
                }
                PresetRow(label = strings.pdfPresetPerDocument, selected = form.preset == PdfPreset.PER_DOCUMENT, enabled = !busyWorking) {
                    form =
                        form.withPreset(PdfPreset.PER_DOCUMENT)
                }
                PresetRow(label = strings.pdfPresetDiagrams, selected = form.preset == PdfPreset.DIAGRAMS, enabled = !busyWorking) {
                    form =
                        form.withPreset(PdfPreset.DIAGRAMS)
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    RadioButton(selected = !form.folderScope, onClick = { form = form.copy(folderScope = false) }, enabled = !busyWorking)
                    Text(strings.pdfScopeAll)
                    RadioButton(selected = form.folderScope, onClick = { form = form.copy(folderScope = true) }, enabled = !busyWorking)
                    Text(strings.pdfScopeFolder)
                }
                if (form.folderScope) {
                    OutlinedTextField(
                        value = form.folder,
                        onValueChange = { form = form.copy(folder = it) },
                        label = { Text(strings.pdfScopeFolderHint) },
                        isError = !form.folderValid,
                        singleLine = true,
                        enabled = !busyWorking,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text(strings.pdfPage, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = form.page == PageFormat.A4,
                        onClick = { form = form.copy(page = PageFormat.A4) },
                        enabled = !busyWorking,
                    )
                    Text("A4")
                    RadioButton(
                        selected = form.page == PageFormat.A3,
                        onClick = { form = form.copy(page = PageFormat.A3) },
                        enabled = !busyWorking,
                    )
                    Text("A3")
                }
                Text(strings.pdfOrientation, style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(
                        PageOrientation.AUTO to strings.pdfOrientationAuto,
                        PageOrientation.PORTRAIT to strings.pdfOrientationPortrait,
                        PageOrientation.LANDSCAPE to strings.pdfOrientationLandscape,
                    ).forEach { (o, label) ->
                        RadioButton(
                            selected = form.orientation == o,
                            onClick = { form = form.copy(orientation = o) },
                            enabled = !busyWorking,
                        )
                        Text(label, modifier = Modifier.padding(end = 8.dp))
                    }
                }

                OptionCheck(
                    label = strings.pdfCover,
                    checked = form.cover,
                    availability = form.availability(PdfOption.COVER),
                    strings = strings,
                    enabled = !busyWorking,
                ) {
                    form =
                        form.copy(cover = it)
                }
                OptionCheck(
                    label = strings.pdfToc,
                    checked = form.toc,
                    availability = form.availability(PdfOption.TOC),
                    strings = strings,
                    enabled = !busyWorking,
                ) { form = form.copy(toc = it) }
                OptionCheck(
                    label = strings.pdfHeaderFooter,
                    checked = form.headerFooter,
                    availability = form.availability(PdfOption.HEADER_FOOTER),
                    strings = strings,
                    enabled = !busyWorking,
                ) {
                    form =
                        form.copy(headerFooter = it)
                }
                OptionCheck(
                    label = strings.pdfDraft,
                    checked = form.draft,
                    availability = form.availability(PdfOption.DRAFT),
                    strings = strings,
                    enabled = !busyWorking,
                ) {
                    form =
                        form.copy(draft = it)
                }

                TextButton(onClick = { form = form.copy(advancedExpanded = !form.advancedExpanded) }) {
                    Text((if (form.advancedExpanded) "▾ " else "▸ ") + strings.pdfAdvanced)
                }
                if (form.advancedExpanded) {
                    OutlinedTextField(form.author, {
                        form = form.copy(author = it)
                    }, label = { Text(strings.pdfAuthor) }, singleLine = true, enabled = !busyWorking, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(form.version, {
                        form = form.copy(version = it)
                    }, label = { Text(strings.pdfVersion) }, singleLine = true, enabled = !busyWorking, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(form.date, {
                        form = form.copy(date = it)
                    }, label = {
                        Text(strings.pdfDate)
                    }, isError = !form.dateValid, singleLine = true, enabled = !busyWorking, modifier = Modifier.fillMaxWidth())
                    OptionCheck(
                        label = strings.pdfSplit,
                        checked = form.split,
                        availability = form.availability(PdfOption.SPLIT),
                        strings = strings,
                        enabled = !busyWorking,
                    ) {
                        form =
                            form.copy(split = it)
                    }
                }

                when (val s = ui) {
                    PdfUiState.Idle -> Unit
                    is PdfUiState.Preparing -> {
                        Text(strings.pdfProgress.format(s.done, s.total), modifier = Modifier.padding(top = 8.dp))
                        if (s.total >
                            0
                        ) {
                            LinearProgressIndicator(progress = { s.done / s.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    PdfUiState.Busy ->
                        Text(
                            strings.pdfBusy,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    is PdfUiState.Warnings -> {
                        if (s.failures.isNotEmpty()) {
                            Text(
                                strings.pdfDiagramFailures.format(
                                    s.failures.size,
                                    s.failures.joinToString { "${it.file}:${it.line}" },
                                ),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        if (s.warnings.isNotEmpty()) {
                            Text(
                                strings.pdfScaleWarning.format(
                                    s.warnings.size,
                                    s.warnings.joinToString {
                                        "${it.documentPath}#${it.blockIndex + 1} (${(it.scale * 100).toInt()}%)"
                                    },
                                ),
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Row(modifier = Modifier.padding(top = 4.dp)) {
                            if (s.warnings.isNotEmpty()) {
                                OutlinedButton(onClick = {
                                    closePrepared()
                                    form = form.withA3Landscape()
                                    check()
                                }) { Text(strings.pdfUseA3Landscape) }
                            }
                            TextButton(onClick = { chooseAndWrite() }) { Text(strings.pdfExportAnyway) }
                        }
                    }
                    is PdfUiState.ConfirmOverwrite -> {
                        Text(
                            if (s.existing.isEmpty()) {
                                strings.pdfOverwriteConfirm
                            } else {
                                strings.pdfOverwriteDirConfirm.format(s.existing.size, s.existing.take(5).joinToString())
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row {
                            Button(
                                onClick = { write(target = s.target, overwrite = true) },
                            ) { Text(strings.pdfSaveButton.removeSuffix("…")) }
                            TextButton(onClick = {
                                closePrepared()
                                ui = PdfUiState.Idle
                            }) { Text(strings.pdfCancel) }
                        }
                    }
                    is PdfUiState.Done -> Text(strings.pdfExportDone.format(s.path), modifier = Modifier.padding(top = 8.dp))
                    is PdfUiState.Failed ->
                        Text(
                            strings.pdfExportFailed.format(s.message),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::check,
                enabled =
                    trusted && form.canSubmit && !busyWorking && ui !is PdfUiState.Warnings && ui !is PdfUiState.ConfirmOverwrite,
            ) {
                Text(strings.pdfExportButton)
            }
        },
        dismissButton = {
            TextButton(onClick = {
                cancelled.set(true)
                closePrepared()
                onDismiss()
            }) { Text(if (ui is PdfUiState.Done) strings.pdfClose else strings.pdfCancel) }
        },
    )
}

@Composable
private fun PresetRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun OptionCheck(
    label: String,
    checked: Boolean,
    availability: PdfOptionAvailability,
    strings: Strings,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val usable = availability == PdfOptionAvailability.Enabled
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked && usable, onCheckedChange = onChange, enabled = enabled && usable)
            Text(label)
        }
        if (availability is PdfOptionAvailability.Disabled) {
            val reason =
                when (availability.reasonKey) {
                    PdfReason.TOC_FOR_DIAGRAMS -> strings.pdfDisabledTocForDiagrams
                    PdfReason.COVER_FOR_DIAGRAMS -> strings.pdfDisabledCoverForDiagrams
                    PdfReason.SPLIT_NOT_DIAGRAMS -> strings.pdfDisabledSplitNotDiagrams
                }
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 40.dp),
            )
        }
    }
}
