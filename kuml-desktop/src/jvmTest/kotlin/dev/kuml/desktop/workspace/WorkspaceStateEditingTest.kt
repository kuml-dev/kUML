package dev.kuml.desktop.workspace

import dev.kuml.desktop.i18n.Strings
import dev.kuml.workspace.OkfWriteResult
import dev.kuml.workspace.WorkspaceScanner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files

/**
 * Tests for the editable-workspace additions to [WorkspaceState] (V-next): the in-memory
 * buffer, dirty tracking, frontmatter splicing, revalidation, and [WorkspaceState.save]'s
 * gate/write/incremental-reparse path. [WorkspaceStateTest] covers the pre-existing
 * read-only selection/render behaviour and is left untouched as the regression anchor.
 */
class WorkspaceStateEditingTest :
    FunSpec({

        fun writeSampleWorkspace(): File {
            val root = Files.createTempDirectory("kuml-workspace-editing-test").toFile()

            File(root, "index.md").writeText(
                """
                ---
                type: KumlWorkspace
                ---
                # Index
                """.trimIndent(),
            )

            File(root, "prose.md").writeText(
                """
                ---
                type: Concept
                title: Original Title
                ---
                # Prose article — no diagram here.
                """.trimIndent(),
            )

            File(root, "diagram.md").writeText(
                """
                ---
                type: UmlClassDiagram
                ---
                # A class diagram

                ```kuml
                classDiagram(name = "Test") {
                    classOf(name = "Fahrzeug") { }
                }
                ```
                """.trimIndent(),
            )

            return root
        }

        test("select fills the buffer with the file's content and isDirty is false") {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val state = WorkspaceState(workspace)
                val doc = state.documents.first { it.relativePath == "prose.md" }

                state.select(doc = doc, themeName = "plain", strings = Strings.EN)

                state.buffer shouldBe doc.file.readText(Charsets.UTF_8)
                state.isDirty shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        test("updateBuffer makes isDirty true; reverting to the original content makes it false again") {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val state = WorkspaceState(workspace)
                val doc = state.documents.first { it.relativePath == "prose.md" }
                state.select(doc = doc, themeName = "plain", strings = Strings.EN)
                val original = state.buffer

                state.updateBuffer(original + "\nmore text")
                state.isDirty shouldBe true

                state.updateBuffer(original!!)
                state.isDirty shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        test(
            "discardChanges reverts the buffer to the last selected/saved content and clears " +
                "isDirty -- bugfix, review finding (MainWindow's `confirmUnsavedAndThen` DISCARD " +
                "path used to leave a Knowledge document's dirty buffer untouched, so the very " +
                "next guarded action re-armed the same unsaved-changes dialog)",
        ) {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val state = WorkspaceState(workspace)
                val doc = state.documents.first { it.relativePath == "prose.md" }
                state.select(doc = doc, themeName = "plain", strings = Strings.EN)
                val original = state.buffer

                state.updateBuffer(original + "\nmore text")
                state.isDirty shouldBe true

                state.discardChanges()

                state.buffer shouldBe original
                state.isDirty shouldBe false
                // The file on disk was never touched -- this is a discard, not a save.
                doc.file.readText(Charsets.UTF_8) shouldBe original
            } finally {
                root.deleteRecursively()
            }
        }

        test("discardChanges before the first select is a no-op (both buffer and savedBuffer are null)") {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val state = WorkspaceState(workspace)

                state.discardChanges()

                state.buffer shouldBe null
                state.isDirty shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        test("setFrontmatterField changes only the buffer — the file on disk is untouched") {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val state = WorkspaceState(workspace)
                val doc = state.documents.first { it.relativePath == "prose.md" }
                val originalFileContent = doc.file.readText(Charsets.UTF_8)
                state.select(doc = doc, themeName = "plain", strings = Strings.EN)

                state.setFrontmatterField(key = "type", value = "Article")

                state.buffer.shouldNotBeNull() shouldContain "type: Article"
                doc.file.readText(Charsets.UTF_8) shouldBe originalFileContent
                state.isDirty shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("save happy path: file written, documents/graphIndex/selected refreshed, isDirty false") {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val state = WorkspaceState(workspace)
                val proseDoc = state.documents.first { it.relativePath == "prose.md" }
                state.select(doc = proseDoc, themeName = "plain", strings = Strings.EN)

                // Add a brand-new outgoing link to index.md -- the graphIndex rebuild is the
                // key regression anchor against a stale, un-invalidated index.
                val newContent = state.buffer + "\n\n[back](./index.md)\n"
                state.updateBuffer(newContent)

                val result = state.save(themeName = "plain", strings = Strings.EN)

                result.shouldBeInstanceOf<OkfWriteResult.Written>()
                proseDoc.file.readText(Charsets.UTF_8) shouldBe newContent
                state.isDirty shouldBe false

                val reselected = state.documents.first { it.relativePath == "prose.md" }
                reselected shouldBe state.selected
                reselected.links.map { it.target } shouldContain "./index.md"

                val indexDoc = state.documents.first { it.relativePath == "index.md" }
                state.graphIndex.backlinks(indexDoc).map { it.from.relativePath } shouldContain "prose.md"
            } finally {
                root.deleteRecursively()
            }
        }

        test(
            "save() clears isDirty BEFORE the incremental reparse+render, not after it -- " +
                "regression, review finding: `savedBuffer`/`findings` used to be assigned only " +
                "AFTER `applySaved()` (a `Dispatchers.IO` re-parse followed by the full render " +
                "pipeline -- script eval + ELK layout, documented elsewhere as 1-3s), so " +
                "`isDirty` stayed `true` for that whole window even though the bytes were " +
                "already on disk; a \"Verwerfen\" landing in that window reverted `buffer` to " +
                "the pre-save content, and the still-in-flight save then overwrote " +
                "`savedBuffer` with the NEW content anyway -- `buffer`(old) != " +
                "`savedBuffer`(new) left `isDirty` `true` again with the wrong text in the " +
                "editor, and the next Ctrl+S clobbered the just-saved file. Targets `isRendering` " +
                "rather than the disk bytes themselves: `applySaved`'s render step is the one " +
                "genuinely slow (hundreds of ms to seconds, per the review finding's own " +
                "measurements) part of the post-write path, so polling for it -- instead of " +
                "racing dispatcher-hop microseconds around the disk write, which would be flaky " +
                "regardless of the fix -- reliably lands inside the exact window `applySaved` " +
                "used to leave `isDirty` wrongly `true` in.",
        ) {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val state = WorkspaceState(workspace)
                // Has a ```kuml block (unlike prose.md) so applySaved's post-write
                // renderCurrentBlock() actually runs the render pipeline instead of returning
                // immediately -- see renderCurrentBlock's early `?: return` for a blockless buffer.
                val doc = state.documents.first { it.relativePath == "diagram.md" }
                state.select(doc = doc, themeName = "plain", strings = Strings.EN)
                state.updateBuffer(state.buffer + "\n")

                coroutineScope {
                    // Dispatchers.Default (a real thread pool), NOT the dispatcher a bare
                    // `launch` would inherit here (kotest runs this suspend test body via
                    // `runBlocking`, whose implicit dispatcher is a single-threaded EVENT LOOP
                    // tied to THIS thread). `save()`'s `withContext(Dispatchers.IO) { ... }`
                    // always resumes its continuation back on the ORIGINAL dispatcher once the
                    // IO block finishes -- if that original dispatcher were `runBlocking`'s own
                    // event loop, the busy `while` below (which never suspends, so it never lets
                    // that event loop pump the pending resumption) would deadlock the coroutine
                    // at that exact resume point for the whole 10s deadline, never reaching
                    // `isRendering = true` at all (this is genuinely what happened when this
                    // test was first written without this dispatcher — not a product bug).
                    // `Dispatchers.Default` resumes on its OWN thread pool, independent of
                    // whatever this thread's busy-loop is doing, so the two run truly
                    // concurrently. `CoroutineStart.UNDISPATCHED` still makes the initial
                    // synchronous portion (everything up to `save()`'s first real suspension)
                    // run right here before `launch(...)` returns.
                    val saving =
                        launch(context = Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            state.save(themeName = "plain", strings = Strings.EN)
                        }

                    var sawRendering = false
                    var isDirtyWhileRendering = true
                    val deadlineNanos = System.nanoTime() + 10_000_000_000L // 10s ceiling
                    while (saving.isActive && System.nanoTime() < deadlineNanos) {
                        if (state.isRendering) {
                            sawRendering = true
                            isDirtyWhileRendering = state.isDirty
                            break
                        }
                    }
                    saving.join()

                    sawRendering shouldBe true // sanity: the polling loop actually caught the render in flight
                    isDirtyWhileRendering shouldBe false
                }
            } finally {
                root.deleteRecursively()
            }
        }

        test(
            "a select() that lands while an earlier save() write is still in flight is not " +
                "reverted back to the just-saved document afterwards -- regression, review " +
                "finding, CRITICAL: applySaved() used to reassign `selected` (and `save()` used " +
                "to reassign `savedBuffer`/`findings`), and kick off a render, unconditionally " +
                "once the write's incremental reparse finished -- even if the user had already " +
                "clicked to a DIFFERENT document while the write was still in flight. That left " +
                "`selected` pointing back at the just-saved document while `buffer` already held " +
                "the newly-selected document's text, so the very next Ctrl+S would silently " +
                "overwrite the just-saved document's file with the OTHER document's content.",
        ) {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val state = WorkspaceState(workspace)
                val docA = state.documents.first { it.relativePath == "prose.md" }
                val docB = state.documents.first { it.relativePath == "index.md" }
                val originalBContent = docB.file.readText(Charsets.UTF_8)

                state.select(doc = docA, themeName = "plain", strings = Strings.EN)
                state.updateBuffer(state.buffer + "\nedited before save\n")
                val editedAContent = state.buffer

                coroutineScope {
                    // UNDISPATCHED: runs save()'s synchronous prefix -- capturing its own
                    // `token` snapshot BEFORE launching the `Dispatchers.IO` write -- right
                    // here, before `launch` returns. That guarantees the `select()` call below
                    // bumps `selectionToken` strictly AFTER `save()` already took its snapshot,
                    // deterministically reproducing the race window regardless of how the two
                    // coroutines actually get scheduled afterwards (see `save()`'s own KDoc for
                    // why the snapshot must happen before the write).
                    val saving =
                        launch(context = Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            state.save(themeName = "plain", strings = Strings.EN)
                        }

                    state.select(doc = docB, themeName = "plain", strings = Strings.EN)

                    saving.join()
                }

                // The write itself succeeded -- docA's file has the edited content on disk.
                docA.file.readText(Charsets.UTF_8) shouldBe editedAContent

                // But the SELECTION must still be docB, with docB's own buffer -- not reverted
                // back to docA by the since-superseded save().
                state.selected?.relativePath shouldBe "index.md"
                state.buffer shouldBe originalBContent
                state.isDirty shouldBe false

                // A subsequent save (the user believes they are saving docB) must write docB's
                // own content back to docB's own file -- NOT clobber docA with it.
                val result = state.save(themeName = "plain", strings = Strings.EN)
                result.shouldBeInstanceOf<OkfWriteResult.Written>()
                docB.file.readText(Charsets.UTF_8) shouldBe originalBContent
                docA.file.readText(Charsets.UTF_8) shouldBe editedAContent
            } finally {
                root.deleteRecursively()
            }
        }

        test(
            "select() resolves a stale OkfDocument instance against the current documents " +
                "list instead of assigning it to `selected` as-is -- regression, review " +
                "finding: MainWindow's `confirmUnsavedAndThen` SAVE path captures the tree " +
                "row's `OkfDocument` BEFORE the guarded `save()` completes, then calls " +
                "`select()` with that pre-save instance once `save()` (and its `applySaved` " +
                "swap of a freshly reparsed instance into `documents`) has already finished. " +
                "Assigning the stale instance directly used to leave `selected` data-class-" +
                "UNEQUAL to its own entry in `documents` whenever the save changed frontmatter/" +
                "kuml-blocks/links -- WorkspaceTreePane's `doc == selected` row-highlight and " +
                "live-type-badge checks then failed for every row until the user clicked again.",
        ) {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val state = WorkspaceState(workspace)
                val preSaveDoc = state.documents.first { it.relativePath == "prose.md" }
                state.select(doc = preSaveDoc, themeName = "plain", strings = Strings.EN)

                state.setFrontmatterField(key = "title", value = "Neuer Titel")
                val result = state.save(themeName = "plain", strings = Strings.EN)
                result.shouldBeInstanceOf<OkfWriteResult.Written>()

                // The save already re-pointed `selected` at the freshly reparsed instance --
                // confirm the up-front invariant this test guards against regressing.
                state.documents shouldContain state.selected

                // Re-select using the STALE, pre-save instance -- exactly what
                // `confirmUnsavedAndThen`'s captured `action()` does.
                state.select(doc = preSaveDoc, themeName = "plain", strings = Strings.EN)

                state.documents shouldContain state.selected
                state.selected?.relativePath shouldBe "prose.md"
                state.selected?.frontmatter?.title shouldBe "Neuer Titel"
            } finally {
                root.deleteRecursively()
            }
        }

        test("save with an unrecognised type: is Blocked, the file stays untouched") {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val state = WorkspaceState(workspace)
                val doc = state.documents.first { it.relativePath == "prose.md" }
                val original = doc.file.readText(Charsets.UTF_8)
                state.select(doc = doc, themeName = "plain", strings = Strings.EN)

                state.setFrontmatterField(key = "type", value = "Quatsch")
                val result = state.save(themeName = "plain", strings = Strings.EN)

                result.shouldBeInstanceOf<OkfWriteResult.Blocked>()
                doc.file.readText(Charsets.UTF_8) shouldBe original
                state.blockingFindings.map { it.code } shouldContain "OKF-W-002"
            } finally {
                root.deleteRecursively()
            }
        }

        test("save with missing frontmatter is Blocked with OKF-E-001") {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val state = WorkspaceState(workspace)
                val doc = state.documents.first { it.relativePath == "prose.md" }
                state.select(doc = doc, themeName = "plain", strings = Strings.EN)

                state.updateBuffer("# No frontmatter at all, just prose.")
                val result = state.save(themeName = "plain", strings = Strings.EN)

                result.shouldBeInstanceOf<OkfWriteResult.Blocked>()
                state.blockingFindings.map { it.code } shouldContain "OKF-E-001"
            } finally {
                root.deleteRecursively()
            }
        }

        test("save with a broken link is Written, with the warning surfaced in findings") {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val state = WorkspaceState(workspace)
                val doc = state.documents.first { it.relativePath == "prose.md" }
                state.select(doc = doc, themeName = "plain", strings = Strings.EN)

                state.updateBuffer(state.buffer + "\n\n[broken](./nowhere.md)\n")
                val result = state.save(themeName = "plain", strings = Strings.EN)

                result.shouldBeInstanceOf<OkfWriteResult.Written>()
                state.findings.map { it.code } shouldContain "OKF-E-005"
                state.blockingFindings.shouldBeEmpty()
            } finally {
                root.deleteRecursively()
            }
        }

        test("revalidate reports OKF-E-003 as a non-blocking warning for a diagram type with no kuml block") {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val state = WorkspaceState(workspace)
                val doc = state.documents.first { it.relativePath == "prose.md" }
                state.select(doc = doc, themeName = "plain", strings = Strings.EN)

                state.setFrontmatterField(key = "type", value = "UmlClassDiagram")
                state.revalidate()

                state.findings.map { it.code } shouldContain "OKF-E-003"
                state.blockingFindings.shouldBeEmpty()
            } finally {
                root.deleteRecursively()
            }
        }

        test(
            "a concurrent renderCurrentBlock() for the SAME document does not discard an " +
                "in-flight revalidate()'s result -- regression for the selectionToken race",
        ) {
            // Mirrors KnowledgeWorkspaceScreen's two independent debounced LaunchedEffect
            // collectors, which both fire off the SAME buffer edit: one calls revalidate(),
            // the other calls renderCurrentBlock() -- both for the SAME selected document.
            // Before the fix, renderCurrentBlock() bumped the very same `selectionToken` that
            // revalidate() reads to detect a DOCUMENT SWITCH, so a same-document render running
            // concurrently made revalidate() wrongly believe the document had been switched out
            // and discard its own result -- `findings` was then never updated for that edit at
            // all (e.g. `type: Quatsch` never showed the OKF-W-002 banner).
            //
            // Test-quality fix (review finding) -- the naive `launch { state.revalidate() }`
            // below only reproduced the race by ACCIDENT: kotest runs a `suspend`-lambda test
            // body on a real (non-`runTest`/`StandardTestDispatcher`) coroutine dispatcher, so
            // whether the launched `revalidate()` actually read `selectionToken` BEFORE or
            // AFTER the immediately-following `renderCurrentBlock()` call bumped it was down to
            // real thread scheduling -- a future regression that re-merges the two counters
            // could pass or fail this test depending on luck. `CoroutineStart.UNDISPATCHED`
            // makes the ordering deterministic instead of hoping for it: it runs the launched
            // coroutine synchronously, on this thread, up to its first real suspension point
            // (`revalidate()`'s `withContext(Dispatchers.Default)`) -- i.e. `launch(...)`
            // returning here is a hard guarantee that `revalidate()` has already captured its
            // token BEFORE the `renderCurrentBlock()` call below runs at all.
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val state = WorkspaceState(workspace)
                val doc = state.documents.first { it.relativePath == "diagram.md" }
                state.select(doc = doc, themeName = "plain", strings = Strings.EN)

                state.updateBuffer(state.buffer!!.replace("type: UmlClassDiagram", "type: Quatsch"))

                coroutineScope {
                    val revalidation = launch(start = CoroutineStart.UNDISPATCHED) { state.revalidate() }
                    state.renderCurrentBlock(themeName = "plain", strings = Strings.EN)
                    revalidation.join()
                }

                state.findings.map { it.code } shouldContain "OKF-W-002"
            } finally {
                root.deleteRecursively()
            }
        }

        test(
            "renderCurrentBlock does not leave isRendering stuck true when a newer call for a " +
                "document with no kuml block supersedes an older still-running render -- " +
                "regression, review finding: the three early returns in renderCurrentBlock (no " +
                "buffer, no kuml block, ERM short-circuit) used to run BEFORE `isRendering = " +
                "true` was ever set for THIS call, so they left `isRendering` however the OLDER, " +
                "still-in-flight call's own `finally` block had last set it -- and that older " +
                "call's `finally` gates on `token == renderToken`, which it has already lost by " +
                "the time it runs, so it never gets to clean up either. WorkspacePreviewPane then " +
                "shows an unbounded 'Rendering...' message for a document with no diagram at all.",
        ) {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val state = WorkspaceState(workspace)
                val diagramDoc = state.documents.first { it.relativePath == "diagram.md" }
                val proseDoc = state.documents.first { it.relativePath == "prose.md" }

                coroutineScope {
                    // UNDISPATCHED + Dispatchers.Default, same reasoning as the other tests in
                    // this file that poll `isRendering` while a render is genuinely in flight
                    // (script eval + ELK layout, documented elsewhere as 1-3s) -- Default's own
                    // thread pool lets the busy-poll below and the render run truly concurrently
                    // instead of deadlocking on a shared single-threaded event loop.
                    val selecting =
                        launch(context = Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            state.select(doc = diagramDoc, themeName = "plain", strings = Strings.EN)
                        }

                    val deadlineNanos = System.nanoTime() + 10_000_000_000L // 10s ceiling
                    while (selecting.isActive && !state.isRendering && System.nanoTime() < deadlineNanos) {
                        // busy-poll until the diagram render is actually in flight
                    }
                    state.isRendering shouldBe true // sanity: caught the render in flight

                    // Switch to a document with no kuml block WHILE the diagram render above is
                    // still running -- this is renderCurrentBlock's early `?: return` path, called
                    // from the tail of select(). Directly invoking select() (not launched) runs
                    // its synchronous prefix -- including renderCurrentBlock's `++renderToken` and
                    // the immediate `isRendering = false` reset -- before this call suspends, so
                    // by the time it returns, the older call's token is already superseded.
                    state.select(doc = proseDoc, themeName = "plain", strings = Strings.EN)

                    selecting.join()
                }

                state.isRendering shouldBe false
                state.docSvg shouldBe null
                state.docError shouldBe null
            } finally {
                root.deleteRecursively()
            }
        }

        test(
            "select() re-invoked for the already-selected document while a save() for that " +
                "SAME document is still in flight leaves `selected` a different instance than " +
                "its own entry in `documents` -- rest of the Runde-5 review finding: " +
                "applySaved()'s guard only re-points `selected` at the freshly reparsed instance " +
                "when its OWN token snapshot still matches `selectionToken` once the reparse " +
                "resolves; a select() whose token bump lands INSIDE the in-flight save() moves " +
                "`selectionToken` past that snapshot before the guard runs, so the guard fails " +
                "deterministically (the counter is monotonic and never bumped back down) even " +
                "though `documents`/`workspace`/`graphIndex` were updated unconditionally. This " +
                "is why WorkspaceTreePane's row-selected/type-badge check must compare by " +
                "`relativePath`, never by data-class equality on the whole `OkfDocument`.",
        ) {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val state = WorkspaceState(workspace)
                val doc = state.documents.first { it.relativePath == "prose.md" }
                state.select(doc = doc, themeName = "plain", strings = Strings.EN)

                state.setFrontmatterField(key = "title", value = "Neuer Titel")

                coroutineScope {
                    // UNDISPATCHED: runs save()'s synchronous prefix -- snapshotting its own
                    // `token` from `selectionToken` -- right here, before `launch` returns.
                    val saving =
                        launch(context = Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            state.save(themeName = "plain", strings = Strings.EN)
                        }

                    // Re-click the SAME, already-selected row (the Probe-C scenario: title
                    // edited, Ctrl+S pressed, then the identical tree row clicked again before
                    // the save finishes). Called directly (not launched), its synchronous prefix
                    // -- `++selectionToken` and `selected = resolved` -- runs immediately, i.e.
                    // strictly after save()'s snapshot above, deterministically moving
                    // `selectionToken` past it before `applySaved`'s guard can ever see a match.
                    state.select(doc = state.selected!!, themeName = "plain", strings = Strings.EN)

                    saving.join()
                }

                val reparsed = state.documents.first { it.relativePath == "prose.md" }
                reparsed.frontmatter.title shouldBe "Neuer Titel"

                // The documented residual fragility (not fixed here, and not this test's job to
                // fix -- the review finding's own recommended fix is in WorkspaceTreePane, below):
                // `applySaved`'s guard lost the race, so `selected` was never re-pointed at the
                // freshly reparsed instance and is now data-class-unequal to its own `documents`
                // entry (different `frontmatter.title`).
                state.selected?.frontmatter?.title shouldBe "Original Title"
                state.documents shouldNotContain state.selected

                // What the UI actually needs survives this instance-identity drift: comparing by
                // `relativePath` (WorkspaceTreePane's fix) still correctly recognises the row as
                // selected, unlike a plain `doc == selected`.
                (reparsed.relativePath == state.selected?.relativePath) shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("switching the buffer's type to ErmDiagram short-circuits the preview without evaluating the script") {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val state = WorkspaceState(workspace)
                val doc = state.documents.first { it.relativePath == "diagram.md" }
                state.select(doc = doc, themeName = "plain", strings = Strings.EN)
                state.docSvg.shouldNotBeNull() // sanity: it rendered as UmlClassDiagram first

                state.setFrontmatterField(key = "type", value = "ErmDiagram")
                state.renderCurrentBlock(themeName = "plain", strings = Strings.EN)

                state.docSvg shouldBe null
                state.docError shouldBe Strings.EN.previewErmUnsupported
            } finally {
                root.deleteRecursively()
            }
        }
    })
