package dev.kuml.desktop.editor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea

/**
 * Tests [EditorFindController] against a real, headless `RSyntaxTextArea` -- no visible window
 * needed, `java.awt.headless=true` is enough (verified empirically against rsyntaxtextarea-3.5.3
 * during the review that found the bugs these tests pin down).
 *
 * These are regression tests for three review findings against the original V3.7.4 find bar:
 *  1. typing a growing query used to skip past matches instead of extending/narrowing the
 *     current one, because the search anchor advanced on EVERY hit, including ones triggered
 *     purely by typing.
 *  2. explicit backward navigation (Shift+Enter / the previous-match button) never actually
 *     moved backward — it kept re-finding the same match forever.
 *  3. searches never wrapped around the document.
 */
class EditorFindControllerTest :
    FunSpec({

        // Swing/AWT components can be created and searched against without a real display; this
        // just avoids any toolkit attempting to touch a display connection.
        System.setProperty("java.awt.headless", "true")

        fun newTextArea(text: String): RSyntaxTextArea =
            RSyntaxTextArea().apply {
                this.text = text
                caretPosition = 0
            }

        test("typing a growing query re-searches from the fixed anchor, not the previous match's end") {
            val textArea = newTextArea("foo bar foobar")
            val controller = EditorFindController(textArea)
            controller.beginFind()

            controller.find(query = "f", forward = true, matchCase = false, advance = false) shouldBe true
            textArea.selectionStart shouldBe 0

            controller.find(query = "fo", forward = true, matchCase = false, advance = false) shouldBe true
            textArea.selectionStart shouldBe 0

            controller.find(query = "foo", forward = true, matchCase = false, advance = false) shouldBe true
            textArea.selectionStart shouldBe 0
        }

        test("explicit forward navigation advances past the current match instead of re-finding it") {
            val textArea = newTextArea("foo bar foobar")
            val controller = EditorFindController(textArea)
            controller.beginFind()
            controller.find(query = "foo", forward = true, matchCase = false, advance = false)
            textArea.selectionStart shouldBe 0

            controller.find(query = "foo", forward = true, matchCase = false, advance = true) shouldBe true
            textArea.selectionStart shouldBe 8
        }

        test("explicit backward navigation actually moves to earlier matches") {
            // "aXaXaX" has "a" at 0, 2, 4.
            val textArea = newTextArea("aXaXaX")
            val controller = EditorFindController(textArea)
            controller.beginFind()
            controller.find(query = "a", forward = true, matchCase = false, advance = false)
            textArea.selectionStart shouldBe 0

            controller.find(query = "a", forward = true, matchCase = false, advance = true)
            textArea.selectionStart shouldBe 2
            controller.find(query = "a", forward = true, matchCase = false, advance = true)
            textArea.selectionStart shouldBe 4

            // Two backward steps must land on two DIFFERENT, earlier matches -- not
            // re-find [4,5) over and over (the original bug).
            controller.find(query = "a", forward = false, matchCase = false, advance = true) shouldBe true
            textArea.selectionStart shouldBe 2

            controller.find(query = "a", forward = false, matchCase = false, advance = true) shouldBe true
            textArea.selectionStart shouldBe 0
        }

        test("search wraps around when nothing is left in the searched direction") {
            val textArea = newTextArea("needle in a haystack")
            val controller = EditorFindController(textArea)
            textArea.caretPosition = textArea.document.length
            controller.beginFind()

            // Anchored at the end of the document -- without wrap this would report "no match"
            // for text that plainly exists earlier in the document.
            controller.find(query = "needle", forward = true, matchCase = false, advance = false) shouldBe true
            textArea.selectionStart shouldBe 0
        }
    })
