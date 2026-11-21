package dev.kuml.lsp

import com.google.gson.JsonObject
import dev.kuml.workspace.OkfAliasFix
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either

/** Pure tests for [OkfAliasQuickFix] (no server, no CLI, no fonts, no OS dependency). */
class OkfAliasQuickFixTest :
    FunSpec({

        val uri = "file:///n.md"
        val alias = "Sysml2Bdd"
        val canonical = "Sysml2BlockDefinition"

        fun actionsFor(
            text: String,
            vararg ds: Diagnostic,
        ): List<CodeAction> = OkfAliasQuickFix.actions(uri = uri, text = text, context = ds.toList())

        /** Applies the single TextEdit with LSP line semantics (\n, \r\n, lone \r). */
        fun apply(
            text: String,
            action: CodeAction,
        ): String {
            val edit = action.edit.changes[uri]!!.single()

            fun off(p: Position): Int {
                var line = 0
                var i = 0
                while (line < p.line) {
                    val c = text[i++]
                    if (c == '\r') {
                        if (i < text.length && text[i] == '\n') i++
                        line++
                    } else if (c == '\n') {
                        line++
                    }
                }
                return i + p.character
            }
            return text.substring(0, off(edit.range.start)) + edit.newText + text.substring(off(edit.range.end))
        }

        test("offered: one quick fix with the right kind, title, diagnostic link and edit") {
            val text = "---\ntype: $alias\n---\nbody"
            val d = OkfAliasQuickFix.diagnostic(text = text, uri = uri).shouldNotBeNull()
            d.code.left shouldBe "OKF-I-001"
            d.source shouldBe "kuml-okf"
            d.range shouldBe Range(Position(1, 6), Position(1, 6 + alias.length))
            val actions = actionsFor(text, d)
            actions shouldHaveSize 1
            val a = actions[0]
            a.title shouldBe "Replace alias with canonical type"
            a.kind shouldBe CodeActionKind.QuickFix
            a.diagnostics shouldBe listOf(d)
            a.isPreferred shouldBe true
            val edits = a.edit.changes[uri]!!
            edits shouldHaveSize 1
            edits[0].range shouldBe d.range
            edits[0].newText shouldBe canonical
        }

        test("applying the edit equals canonicalizeType byte for byte (plain, quoted, CRLF, non-ASCII, lone CR)") {
            val cases =
                listOf(
                    "---\ntype: $alias\n---\nbody",
                    "---\ntype: \"$alias\"\n---\nbody",
                    "---\ntype: '$alias'\n---\nbody",
                    "---\r\ntype: $alias\r\ntitle: X\r\n---\r\nbody\r\n",
                    "---\ntitle: Grüße 😀\ntype: $alias\n---\nbody",
                    "---\ntitle: Ünï 😀 Ø\ntype:   $alias  \n---\nbody",
                )
            for (text in cases) {
                val d = OkfAliasQuickFix.diagnostic(text = text, uri = uri)
                if (text.startsWith("﻿")) {
                    // BOM before the fence: not frontmatter, so no diagnostic and no action.
                    d.shouldBeNull()
                    continue
                }
                d.shouldNotBeNull()
                val action = actionsFor(text, d).single()
                apply(text, action) shouldBe OkfAliasFix.canonicalizeType(text)
            }
        }

        test("lone CR line endings: no action, never a wrong edit") {
            val text = "---\rtype: $alias\r---\rbody"
            val d = OkfAliasQuickFix.diagnostic(text = text, uri = uri)
            // findTypeValue splits on \n only, so the frontmatter is not recognised.
            d.shouldBeNull()
            val forged = Diagnostic(Range(Position(1, 6), Position(1, 6 + alias.length)), "x")
            forged.setCode(Either.forLeft<String, Int>("OKF-I-001"))
            actionsFor(text, forged).shouldBeEmpty()
        }

        test("idempotent: after applying the edit neither diagnostic nor action remain") {
            val text = "---\ntype: $alias\n---\nbody"
            val d = OkfAliasQuickFix.diagnostic(text = text, uri = uri)!!
            val fixed = apply(text, actionsFor(text, d).single())
            OkfAliasQuickFix.diagnostic(text = fixed, uri = uri).shouldBeNull()
            actionsFor(fixed, d).shouldBeEmpty()
        }

        test("not offered for other diagnostics") {
            val text = "---\ntype: $alias\n---\nbody"
            val range = OkfAliasQuickFix.diagnostic(text = text, uri = uri)!!.range
            val cli = Diagnostic(range, "cli")
            val w002 = Diagnostic(range, "w").apply { setCode(Either.forLeft<String, Int>("OKF-W-002")) }
            actionsFor(text, cli, w002).shouldBeEmpty()
            OkfAliasQuickFix.diagnostic(text = "---\ntype: Nonsense\n---\n", uri = uri).shouldBeNull()
            OkfAliasQuickFix.diagnostic(text = "---\ntype: $canonical\n---\n", uri = uri).shouldBeNull()
        }

        test("stale document: shifted line, changed value, deleted type line, removed frontmatter") {
            val text = "---\ntype: $alias\n---\nbody"
            val d = OkfAliasQuickFix.diagnostic(text = text, uri = uri)!!
            actionsFor("---\ntitle: X\ntype: $alias\n---\nbody", d).shouldBeEmpty()
            actionsFor("---\ntype: Sysml2Ibd\n---\nbody", d).shouldBeEmpty()
            actionsFor("---\ntitle: X\n---\nbody", d).shouldBeEmpty()
            actionsFor("type: $alias\nbody", d).shouldBeEmpty()
            actionsFor("---\ntype:   $alias\n---\nbody", d).shouldBeEmpty()
        }

        test("mismatching data is rejected, missing data and Map data are tolerated") {
            val text = "---\ntype: $alias\n---\nbody"
            val d = OkfAliasQuickFix.diagnostic(text = text, uri = uri)!!

            fun withData(data: Any?) =
                Diagnostic(d.range, "m").apply {
                    code = d.code
                    this.data = data
                }
            actionsFor(text, withData(JsonObject().apply { addProperty("canonicalId", "Evil") })).shouldBeEmpty()
            actionsFor(text, withData(JsonObject().apply { addProperty("alias", "Other") })).shouldBeEmpty()
            actionsFor(text, withData(mapOf("canonicalId" to "Evil"))).shouldBeEmpty()
            actionsFor(text, withData(null)) shouldHaveSize 1
            actionsFor(text, withData(mapOf("alias" to alias, "canonicalId" to canonical))) shouldHaveSize 1
            // The edit always carries the recomputed canonical id.
            actionsFor(text, withData(null))
                .single()
                .edit.changes[uri]!!
                .single()
                .newText shouldBe canonical
        }
    })
