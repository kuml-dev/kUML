package dev.kuml.desktop.editor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [buildSearchContext] -- the pure [org.fife.ui.rtextarea.SearchContext] builder
 * extracted from [EditorPane]'s find wiring (V3.7.4, design review P8).
 */
class EditorPaneFindTest :
    FunSpec({

        test("carries the query, direction, and match-case flags through") {
            val forward = buildSearchContext(query = "alpha", forward = true, matchCase = true)
            forward.searchFor shouldBe "alpha"
            forward.searchForward shouldBe true
            forward.matchCase shouldBe true
        }

        test("forward = false is preserved") {
            val backward = buildSearchContext(query = "beta", forward = false, matchCase = false)
            backward.searchForward shouldBe false
            backward.matchCase shouldBe false
        }

        test("never interprets the query as a regular expression or whole word") {
            // Security-audit requirement: an arbitrary user string reaching the regex engine of
            // a find-as-you-type field is a catastrophic-backtracking DoS risk on the UI thread.
            val ctx = buildSearchContext(query = "(a+)+$", forward = true, matchCase = false)
            ctx.isRegularExpression shouldBe false
            ctx.wholeWord shouldBe false
        }

        test("marks all matches") {
            val ctx = buildSearchContext(query = "gamma", forward = true, matchCase = false)
            ctx.markAll shouldBe true
        }
    })
