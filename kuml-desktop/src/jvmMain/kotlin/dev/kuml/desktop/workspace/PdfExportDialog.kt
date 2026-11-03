package dev.kuml.desktop.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kuml.cli.workspace.WorkspacePdfExport
import dev.kuml.desktop.i18n.Strings
import dev.kuml.workspace.OkfFinding
import dev.kuml.workspace.WorkspaceScanner
import dev.kuml.workspace.pdf.ExportPhase
import dev.kuml.workspace.pdf.PageFormat
import dev.kuml.workspace.pdf.PageOrientation
import dev.kuml.workspace.pdf.PageStrategy
import dev.kuml.workspace.pdf.PdfExportException
import dev.kuml.workspace.pdf.PdfLayoutSummary
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
internal sealed interface PdfUiState {
    data object Idle : PdfUiState

    data class Preparing(
        val done: Int,
        val total: Int,
    ) : PdfUiState

    data class Warnings(
        val warnings: List<ScaleWarning>,
        val failures: List<OkfFinding> = emptyList(),
        val summary: PdfLayoutSummary = PdfLayoutSummary.EMPTY,
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
 * are shown disabled with the reason. "Check and export" renders and lays out first (cancellable, with progress); every
 * message (progress, warnings, overwrite question, result) appears in a fixed status area above the buttons, so the form
 * never moves. A too-small diagram offers "use A3 landscape" (only with a fixed page size) and "export anyway"; only then
 * a save dialog opens.
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
    /** Workspace-relative path of the selected document, or `null` when none is open (the document scope is then disabled). */
    documentPath: String? = null,
    /** Whether the selected document has unsaved edits; the PDF is built from the saved files on disk. */
    documentDirty: Boolean = false,
    initial: PdfExportFormState = PdfExportFormState(),
) {
    var form by remember { mutableStateOf(initial.copy(documentPath = documentPath)) }
    var ui by remember { mutableStateOf<PdfUiState>(PdfUiState.Idle) }
    var prepared by remember { mutableStateOf<PreparedExport?>(null) }
    val cancelled = remember { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { cancelled.set(true) } }

    val busyWorking = ui is PdfUiState.Preparing
    val view = pdfStatusView(ui = ui, form = form)

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

    fun check(f: PdfExportFormState = form) {
        closePrepared()
        cancelled.set(false)
        ui = PdfUiState.Preparing(done = 0, total = 0)
        val options = f.toOptions(producerVersion = producerVersion)
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
                        ui = PdfUiState.Warnings(warnings = p.warnings, failures = p.failures, summary = p.layoutSummary)
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

                val documentAvailable = form.availability(PdfOption.SCOPE_DOCUMENT) == PdfOptionAvailability.Enabled
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    RadioButton(
                        selected = form.scope == PdfScope.ALL,
                        onClick = { form = form.copy(scope = PdfScope.ALL) },
                        enabled = !busyWorking,
                    )
                    Text(strings.pdfScopeAll)
                    RadioButton(
                        selected = form.scope == PdfScope.FOLDER,
                        onClick = { form = form.copy(scope = PdfScope.FOLDER) },
                        enabled = !busyWorking,
                    )
                    Text(strings.pdfScopeFolder)
                    RadioButton(
                        selected = form.scope == PdfScope.DOCUMENT,
                        onClick = { form = form.copy(scope = PdfScope.DOCUMENT) },
                        enabled = !busyWorking && documentAvailable,
                    )
                    // One line with an ellipsis: a long path must not widen the dialog.
                    Text(
                        text =
                            if (documentAvailable) {
                                strings.previewExport.scopeDocument.format(form.documentPath)
                            } else {
                                strings.previewExport.noDocumentOpen
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (form.scope == PdfScope.DOCUMENT && documentDirty) {
                    Text(
                        strings.previewExport.unsavedHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                if (form.scope == PdfScope.FOLDER) {
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
                Text(strings.pdfPageStrategy, style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(
                        PageStrategy.AUTO to strings.pdfPageStrategyAuto,
                        PageStrategy.FIXED to strings.pdfPageStrategyFixed,
                    ).forEach { (ps, label) ->
                        RadioButton(
                            selected = form.pageStrategy == ps,
                            onClick = { form = form.withPageStrategy(ps) },
                            enabled = !busyWorking,
                        )
                        Text(label, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                Text(
                    strings.pdfPageStrategyHint.format(form.minScalePercent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OptionCheck(
                    label = strings.pdfTiling,
                    checked = form.tiling,
                    availability = PdfOptionAvailability.Enabled,
                    strings = strings,
                    enabled = !busyWorking,
                ) { form = form.copy(tiling = it) }
                val orientationAvailability = form.availability(PdfOption.ORIENTATION)
                val orientationEnabled = orientationAvailability == PdfOptionAvailability.Enabled && !busyWorking
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
                            enabled = orientationEnabled,
                        )
                        Text(label, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                if (orientationAvailability is PdfOptionAvailability.Disabled) {
                    Text(
                        strings.pdfDisabledOrientationAuto,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${strings.pdfMinScale}: ${form.minScalePercent} %", modifier = Modifier.padding(end = 8.dp))
                        OutlinedButton(
                            onClick = { form = form.withMinScalePercent(form.minScalePercent - 5) },
                            enabled = !busyWorking && form.minScalePercent > PdfExportFormState.MIN_SCALE_PERCENT_RANGE.first,
                        ) { Text("−") }
                        OutlinedButton(
                            onClick = { form = form.withMinScalePercent(form.minScalePercent + 5) },
                            enabled = !busyWorking && form.minScalePercent < PdfExportFormState.MIN_SCALE_PERCENT_RANGE.last,
                        ) { Text("+") }
                    }
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
            }
        },
        status = {
            PdfStatusArea(
                view = view,
                strings = strings,
                onUseA3 = {
                    val next = form.withA3Landscape()
                    form = next
                    check(next)
                },
                onExportAnyway = { chooseAndWrite() },
                onOverwrite = { (ui as? PdfUiState.ConfirmOverwrite)?.let { write(target = it.target, overwrite = true) } },
                onCancelOverwrite = {
                    closePrepared()
                    ui = PdfUiState.Idle
                },
            )
        },
        confirmButton = {
            Button(
                onClick = { check() },
                enabled = trusted && form.canSubmit && !busyWorking && !view.blocksPrimary(),
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
                    PdfReason.ORIENTATION_BY_PAGE_STRATEGY -> strings.pdfDisabledOrientationAuto
                    PdfReason.NO_DOCUMENT_OPEN -> strings.previewExport.noDocumentOpen
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
