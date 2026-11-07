package dev.kuml.desktop.workspace

import dev.kuml.cli.export.LatexFolderExportException
import dev.kuml.cli.workspace.LatexRunReport
import dev.kuml.cli.workspace.LatexWorkspaceKind
import dev.kuml.cli.workspace.PreparedLatexRun
import dev.kuml.cli.workspace.WorkspaceLatexRun
import dev.kuml.workspace.WorkspaceScanner
import dev.kuml.workspace.latex.LatexKnowledgeExportException
import java.io.File
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** A rendered export that waits for the user's answer to "replace existing files?". */
internal class LatexPendingWrite(
    val prepared: PreparedLatexRun,
    val target: File,
)

/** [state] is what the dialog shows next; [pending] is set only for [LatexUiState.ConfirmOverwrite]. */
internal class LatexRunOutcome(
    val state: LatexUiState,
    val pending: LatexPendingWrite? = null,
)

/** The scripts are run through this; tests replace it to avoid a real render. */
internal fun interface LatexPreparer {
    fun prepare(
        root: File,
        target: File,
        themeName: String?,
    ): PreparedLatexRun
}

internal object DefaultLatexPreparer : LatexPreparer {
    override fun prepare(
        root: File,
        target: File,
        themeName: String?,
    ): PreparedLatexRun = WorkspaceLatexRun.prepare(root = root, target = target, themeName = themeName)
}

private const val GATE_WAIT_SECONDS = 2L

/**
 * Detects the workspace type for the dialog's label. Does I/O; call it off the UI thread. It never runs a script.
 * `null` if the folder cannot be scanned.
 */
internal fun detectLatexWorkspaceKind(root: File): LatexWorkspaceKind? =
    runCatching {
        val canonical = WorkspaceScanner.canonicalRoot(root)
        val target = WorkspaceLatexRun.defaultTarget(canonical)
        WorkspaceLatexRun.detect(root = canonical, excludes = WorkspaceLatexRun.excludes(root = canonical, target = target))
    }.getOrNull()

/**
 * The whole export on the calling (background) thread: render, apply `strict`, write. This is the one function the
 * dialog calls and the tests call; the dialog adds only state and layout. The shared evaluation gate is held while
 * scripts run, not while files are written. A result computed after [isCancelled] became true is discarded.
 * [onWriting] is called right before the first byte is written; it returns false if the run was cancelled in the
 * meantime (then nothing is written), and true once cancelling is refused for good.
 */
internal fun runWorkspaceLatexExport(
    root: File,
    form: WorkspaceLatexFormState,
    evalGate: Semaphore,
    isCancelled: () -> Boolean,
    onWriting: () -> Boolean = { true },
    preparer: LatexPreparer = DefaultLatexPreparer,
): LatexRunOutcome {
    if (!evalGate.tryAcquire(GATE_WAIT_SECONDS, TimeUnit.SECONDS)) return LatexRunOutcome(state = LatexUiState.Busy)
    val prepared: PreparedLatexRun
    val target: File
    try {
        val canonicalRoot = WorkspaceScanner.canonicalRoot(root)
        // Written to as typed (the guard refuses a symbolic link in it); only the exclusion uses the canonical form.
        target = typedLatexTarget(path = form.targetPath, root = canonicalRoot)
        val excludedTarget = resolveLatexTarget(path = form.targetPath, root = canonicalRoot)
        prepared = preparer.prepare(root = canonicalRoot, target = excludedTarget, themeName = form.themeName)
    } catch (e: LatexKnowledgeExportException) {
        return failed(e)
    } catch (e: LatexFolderExportException) {
        return failed(e)
    } catch (e: IOException) {
        return failed(e)
    } catch (e: IllegalArgumentException) {
        return failed(e)
    } finally {
        evalGate.release()
    }
    if (isCancelled()) return LatexRunOutcome(state = LatexUiState.Cancelled)
    val lines = LatexRunReport.issueLines(prepared)
    val counts = LatexRunReport.counts(prepared)
    if (form.strict && WorkspaceLatexRun.blocksStrict(prepared)) {
        return LatexRunOutcome(state = LatexUiState.StrictBlocked(lines = lines, counts = counts))
    }
    if (prepared.hasNothingToWrite()) return LatexRunOutcome(state = LatexUiState.NothingToExport(lines = lines, counts = counts))
    if (!onWriting()) return LatexRunOutcome(state = LatexUiState.Cancelled)
    return write(pending = LatexPendingWrite(prepared = prepared, target = target), overwrite = false)
}

/** The user chose "Replace": write the kept result again, now allowed to replace existing files. */
internal fun overwriteWorkspaceLatexExport(pending: LatexPendingWrite): LatexRunOutcome = write(pending = pending, overwrite = true)

private fun write(
    pending: LatexPendingWrite,
    overwrite: Boolean,
): LatexRunOutcome {
    val prepared = pending.prepared
    return try {
        val result = WorkspaceLatexRun.write(prepared = prepared, outDir = pending.target, overwrite = overwrite)
        LatexRunOutcome(
            state =
                LatexUiState.Done(
                    outDir = result.outDir,
                    summary = LatexRunReport.summaryLines(prepared = prepared, result = result),
                    lines = LatexRunReport.issueLines(prepared),
                    counts = LatexRunReport.counts(prepared),
                ),
        )
    } catch (e: LatexFolderExportException) {
        if (e.code == LatexFolderExportException.Code.OUTPUT_EXISTS && !overwrite) {
            val existing = WorkspaceLatexRun.existingTargets(prepared = prepared, outDir = pending.target)
            LatexRunOutcome(state = LatexUiState.ConfirmOverwrite(existing = existing.map(LatexRunReport::shown)), pending = pending)
        } else {
            failed(e)
        }
    } catch (e: IOException) {
        failed(e)
    }
}

private fun failed(e: Exception): LatexRunOutcome =
    LatexRunOutcome(
        state =
            when (e) {
                is IOException -> LatexUiState.Failed(message = e.javaClass.simpleName, ioClass = e.javaClass.simpleName)
                else -> LatexUiState.Failed(message = LatexRunReport.shown(e.message ?: e.javaClass.simpleName))
            },
    )

/**
 * [runWorkspaceLatexExport] for the dialog: an unexpected exception becomes a [LatexUiState.Failed] carrying only the
 * exception's class name, so the dialog never dies silently and never shows a raw message. Errors (out of memory
 * and the like) are not caught.
 */
internal fun runWorkspaceLatexExportSafely(
    root: File,
    form: WorkspaceLatexFormState,
    evalGate: Semaphore,
    isCancelled: () -> Boolean,
    onWriting: () -> Boolean = { true },
): LatexRunOutcome =
    runCatching {
        runWorkspaceLatexExport(root = root, form = form, evalGate = evalGate, isCancelled = isCancelled, onWriting = onWriting)
    }.getOrElse {
        if (it is Error) throw it
        LatexRunOutcome(state = LatexUiState.Failed(message = it.javaClass.simpleName))
    }

/** [overwriteWorkspaceLatexExport] with the same safety net as [runWorkspaceLatexExportSafely]. */
internal fun overwriteWorkspaceLatexExportSafely(pending: LatexPendingWrite): LatexRunOutcome =
    runCatching { overwriteWorkspaceLatexExport(pending) }.getOrElse {
        if (it is Error) throw it
        LatexRunOutcome(state = LatexUiState.Failed(message = it.javaClass.simpleName))
    }
