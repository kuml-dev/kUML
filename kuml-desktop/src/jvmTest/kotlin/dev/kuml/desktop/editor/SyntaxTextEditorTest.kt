package dev.kuml.desktop.editor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Cheap smoke test against a real, headless `RSyntaxTextArea` (no Compose harness needed —
 * `SyntaxTextEditor` itself is exercised manually, see the plan's P-6 verification note):
 * guards against a typo'd syntax-style constant string silently falling back to plain text
 * with no compile-time signal (`syntaxEditingStyle` takes a bare `String`, not an enum).
 */
class SyntaxTextEditorTest :
    FunSpec({

        System.setProperty("java.awt.headless", "true")

        test("SYNTAX_STYLE_KOTLIN is accepted by a real RSyntaxTextArea") {
            val area = RSyntaxTextArea().apply { syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_KOTLIN }
            area.syntaxEditingStyle shouldBe SyntaxConstants.SYNTAX_STYLE_KOTLIN
        }

        test("SYNTAX_STYLE_MARKDOWN is accepted by a real RSyntaxTextArea") {
            val area = RSyntaxTextArea().apply { syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_MARKDOWN }
            area.syntaxEditingStyle shouldBe SyntaxConstants.SYNTAX_STYLE_MARKDOWN
        }

        // ── isExternalTextChangeANewDocument() / applyExternalTextChange() — bugfix, review finding ──
        // Regression for: a single keystroke in the title field (or a type-dropdown change)
        // spliced the WHOLE buffer via FrontmatterWriter.setField, which used to always be
        // treated as "a different document was opened" -- wiping the entire undo history and
        // resetting the caret to 0, even mid-edit on a 200-line document.

        test("null documentKey always reports a new document (preserves the original single-document-editor behaviour)") {
            isExternalTextChangeANewDocument(lastDocumentKey = null, documentKey = null) shouldBe true
            isExternalTextChangeANewDocument(lastDocumentKey = "a", documentKey = null) shouldBe true
        }

        test("an unchanged, non-null documentKey is NOT a new document") {
            isExternalTextChangeANewDocument(lastDocumentKey = "concept.md", documentKey = "concept.md") shouldBe false
        }

        test("a changed, non-null documentKey IS a new document") {
            isExternalTextChangeANewDocument(lastDocumentKey = "concept.md", documentKey = "other.md") shouldBe true
        }

        test("applyExternalTextChange for a NEW document clears undo history and resets the caret") {
            val area = RSyntaxTextArea().apply { text = "line one\nline two\nline three" }
            area.discardAllEdits() // baseline: start from a clean history, like the real editor's initial setup
            area.caretPosition = 5
            area.replaceRange("EDIT", 0, 4) // an undoable edit that must NOT survive a new-document replace

            applyExternalTextChange(textArea = area, newText = "brand new content", isNewDocument = true)

            area.text shouldBe "brand new content"
            area.canUndo() shouldBe false
            area.caretPosition shouldBe 0
        }

        test("applyExternalTextChange for the SAME document preserves the caret position and keeps the PRE-EXISTING undo history") {
            val area = RSyntaxTextArea().apply { text = "Hello World" }
            area.discardAllEdits() // baseline: undo history starts clean
            area.replaceRange("EDIT", 0, 5) // "EDIT World" -- an in-progress edit whose history must survive
            area.caretPosition = 4

            // Simulates a title-field splice (FrontmatterWriter.setField) replacing the whole
            // buffer of the SAME document.
            val spliced = "EDITX World"
            applyExternalTextChange(textArea = area, newText = spliced, isNewDocument = false)

            area.text shouldBe spliced
            area.caretPosition shouldBe 4

            // Undo the splice itself -- lands back on the PRE-splice text ("EDIT World"),
            // proving the splice became one more undoable step rather than clearing history.
            area.undoLastAction()
            area.text shouldBe "EDIT World"
            // A SECOND undo reaches further back into history that pre-dates the splice
            // entirely -- exactly what `discardAllEdits()` would have destroyed.
            area.canUndo() shouldBe true
            area.undoLastAction()
            area.text shouldBe "Hello World"
        }

        test("applyExternalTextChange for the SAME document clamps a caret beyond the new (shorter) text's length") {
            val area = RSyntaxTextArea().apply { text = "a very long original line of text" }
            area.caretPosition = area.text.length

            applyExternalTextChange(textArea = area, newText = "short", isNewDocument = false)

            area.text shouldBe "short"
            area.caretPosition shouldBe 5
        }

        // ── applyExternalTextChangeSilently() — CRITICAL bugfix, review finding ──
        // Regression for: EVERY external text replacement (a title-field/type-dropdown splice
        // via `FrontmatterWriter.setField`, or a plain File ▸ Open) wiped the entire document.
        // `RSyntaxTextArea.setText()` fires several `DocumentEvent`s while `AbstractDocument`
        // internally does `remove(0, len)` then `insertString(...)` — the document is
        // genuinely, observably EMPTY for the first of those events on a real (headless)
        // `RSyntaxTextArea`, not merely in theory. A listener with no way to tell "this is one
        // of those transient events" from "the user really did clear the document" ends up
        // propagating that empty state outward. See [applyExternalTextChangeSilently]'s KDoc
        // for the full mechanism this closes.

        test(
            "a real RSyntaxTextArea.text= replace fires an intermediate empty-document " +
                "DocumentEvent when nothing suppresses it -- this is the mechanism the CRITICAL " +
                "data-loss bug depended on, not a hypothetical",
        ) {
            val area = RSyntaxTextArea().apply { text = "line one\nline two\nline three" }
            val observedTexts = mutableListOf<String>()
            area.document.addDocumentListener(
                object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent) = record()

                    override fun removeUpdate(e: DocumentEvent) = record()

                    override fun changedUpdate(e: DocumentEvent) = record()

                    private fun record() {
                        observedTexts += area.text
                    }
                },
            )

            applyExternalTextChange(textArea = area, newText = "brand new content", isNewDocument = false)

            // The unsuppressed replace really does leak a transient "" to the listener --
            // exactly the value that used to round-trip out as `onTextChange("")`.
            observedTexts shouldContain ""
        }

        test(
            "applyExternalTextChangeSilently suppresses every DocumentEvent fired mid-replace, " +
                "including the transient empty-document ones -- the actual fix",
        ) {
            val area = RSyntaxTextArea().apply { text = "line one\nline two\nline three" }
            val suppress = AtomicBoolean(false)
            val observedTexts = mutableListOf<String>()
            area.document.addDocumentListener(
                object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent) = record()

                    override fun removeUpdate(e: DocumentEvent) = record()

                    override fun changedUpdate(e: DocumentEvent) = record()

                    private fun record() {
                        // Mirrors SyntaxTextEditor's real listener: bail out first, exactly
                        // like `onChanged()` does, before looking at `area.text` at all.
                        if (suppress.get()) return
                        observedTexts += area.text
                    }
                },
            )

            applyExternalTextChangeSilently(
                textArea = area,
                newText = "brand new content",
                isNewDocument = false,
                suppressListenerNotifications = suppress,
            )

            observedTexts.shouldNotContain("")
            observedTexts.shouldBeEmpty()
            area.text shouldBe "brand new content"
            suppress.get() shouldBe false // restored after the call, not left stuck "on"
        }
    })
