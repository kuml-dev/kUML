package dev.kuml.desktop.editor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class ReplaceAllUndoableTest :
    FunSpec({
        System.setProperty("java.awt.headless", "true")

        test("replaceAllAsSingleUndoableEdit is undone by exactly ONE undo") {
            val area = RSyntaxTextArea().apply { text = "original text" }
            area.discardAllEdits()
            replaceAllAsSingleUndoableEdit(textArea = area, newText = "completely new\nmulti-line content")
            area.text shouldBe "completely new\nmulti-line content"
            area.canUndo() shouldBe true
            area.undoLastAction()
            area.text shouldBe "original text"
            area.canUndo() shouldBe false
        }

        test("the document listener observes the change (so the dirty flag would be set)") {
            val area = RSyntaxTextArea().apply { text = "abc" }
            val seen = mutableListOf<String>()
            area.document.addDocumentListener(
                object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent) {
                        seen += area.text
                    }

                    override fun removeUpdate(e: DocumentEvent) {
                        seen += area.text
                    }

                    override fun changedUpdate(e: DocumentEvent) = Unit
                },
            )
            replaceAllAsSingleUndoableEdit(textArea = area, newText = "xyz")
            seen.isNotEmpty() shouldBe true
            seen.last() shouldBe "xyz"
        }

        test("redo after undo restores the replacement") {
            val area = RSyntaxTextArea().apply { text = "a" }
            area.discardAllEdits()
            replaceAllAsSingleUndoableEdit(textArea = area, newText = "b")
            area.undoLastAction()
            area.redoLastAction()
            area.text shouldBe "b"
        }
    })
