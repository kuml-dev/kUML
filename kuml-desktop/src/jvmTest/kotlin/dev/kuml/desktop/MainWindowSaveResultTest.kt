package dev.kuml.desktop

import dev.kuml.desktop.i18n.Strings
import dev.kuml.workspace.WorkspaceWriteGuard
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Pure-function tests for [rejectionMessage] -- the cause->message mapping extracted out of
 * MainWindow's `reportKnowledgeSaveResult` (bugfix, review finding: every
 * [WorkspaceWriteGuard.Rejection] used to be reported with [Strings.docSaveOutsideRoot],
 * regardless of the actual cause) so it is testable without a Compose/Swing harness, same
 * reasoning as [MainWindowRenderTriggerTest].
 */
class MainWindowSaveResultTest :
    FunSpec({

        test("every Rejection reason maps to a distinct, non-empty EN message") {
            val messages = WorkspaceWriteGuard.Rejection.entries.map { rejectionMessage(rejection = it, strings = Strings.EN) }
            messages.forEach { it.isNotEmpty() shouldBe true }
            messages.toSet().size shouldBe WorkspaceWriteGuard.Rejection.entries.size
        }

        test("every Rejection reason maps to a distinct, non-empty DE message") {
            val messages = WorkspaceWriteGuard.Rejection.entries.map { rejectionMessage(rejection = it, strings = Strings.DE) }
            messages.forEach { it.isNotEmpty() shouldBe true }
            messages.toSet().size shouldBe WorkspaceWriteGuard.Rejection.entries.size
        }

        test("SYMLINK maps to its own message, not docSaveOutsideRoot (the original bug)") {
            val message = rejectionMessage(rejection = WorkspaceWriteGuard.Rejection.SYMLINK, strings = Strings.EN)
            message shouldBe Strings.EN.docSaveRejectedSymlink
            message shouldNotBe Strings.EN.docSaveOutsideRoot
        }

        test("NOT_A_REGULAR_FILE maps to its own message, not docSaveOutsideRoot") {
            val message = rejectionMessage(rejection = WorkspaceWriteGuard.Rejection.NOT_A_REGULAR_FILE, strings = Strings.EN)
            message shouldBe Strings.EN.docSaveRejectedNotRegularFile
            message shouldNotBe Strings.EN.docSaveOutsideRoot
        }

        test("UNKNOWN_DOCUMENT maps to its own message, not docSaveOutsideRoot") {
            val message = rejectionMessage(rejection = WorkspaceWriteGuard.Rejection.UNKNOWN_DOCUMENT, strings = Strings.EN)
            message shouldBe Strings.EN.docSaveRejectedUnknownDocument
            message shouldNotBe Strings.EN.docSaveOutsideRoot
        }

        test("IO_ERROR maps to its own message, not docSaveOutsideRoot") {
            val message = rejectionMessage(rejection = WorkspaceWriteGuard.Rejection.IO_ERROR, strings = Strings.EN)
            message shouldBe Strings.EN.docSaveRejectedIoError
            message shouldNotBe Strings.EN.docSaveOutsideRoot
        }

        test("OUTSIDE_ROOT still maps to docSaveOutsideRoot (unchanged, pre-existing behaviour)") {
            rejectionMessage(rejection = WorkspaceWriteGuard.Rejection.OUTSIDE_ROOT, strings = Strings.EN) shouldBe
                Strings.EN.docSaveOutsideRoot
        }
    })
