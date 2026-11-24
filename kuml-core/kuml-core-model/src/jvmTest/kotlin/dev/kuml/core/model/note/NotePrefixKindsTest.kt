@file:Suppress("RequireNamedArguments")

package dev.kuml.core.model.note

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.system.measureTimeMillis

class NotePrefixKindsTest :
    FunSpec(body = {
        val table = NotePrefixKinds(mapOf("ANNAHME" to NoteKind.ASSUMPTION, "Risiko" to NoteKind.RISK, "ÄNDERUNG" to NoteKind.DECISION))

        test("hit returns the lowercase key and the expected kind") {
            table.expectedKindFor("ANNAHME: Der Nutzer ist online") shouldBe ("annahme" to NoteKind.ASSUMPTION)
        }

        test("matching is case-insensitive") {
            for (p in listOf("annahme", "Annahme", "ANNAHME")) {
                table.expectedKindFor("$p: x") shouldBe ("annahme" to NoteKind.ASSUMPTION)
            }
            table.expectedKindFor("RISIKO Ausfall")?.second shouldBe NoteKind.RISK
        }

        test("terminator colon, whitespace and leading whitespace") {
            table.expectedKindFor("ANNAHME:x")?.second shouldBe NoteKind.ASSUMPTION
            table.expectedKindFor("ANNAHME x")?.second shouldBe NoteKind.ASSUMPTION
            table.expectedKindFor("  \n ANNAHME: x")?.second shouldBe NoteKind.ASSUMPTION
            table.expectedKindFor("ANNAHME")?.second shouldBe NoteKind.ASSUMPTION
        }

        test("unknown prefix and near misses do not match") {
            table.expectedKindFor("HINWEIS: x") shouldBe null
            table.expectedKindFor("ANNAHMEN: x") shouldBe null
            table.expectedKindFor("ANNAHME-3: x") shouldBe null
            table.expectedKindFor(": x") shouldBe null
            table.expectedKindFor("") shouldBe null
            table.expectedKindFor("Die ANNAHME: x") shouldBe null
        }

        test("empty table never matches") {
            NotePrefixKinds.EMPTY.isEmpty shouldBe true
            NotePrefixKinds.EMPTY.expectedKindFor("ANNAHME: x") shouldBe null
        }

        test("unicode prefix") {
            table.expectedKindFor("Änderung: neu")?.second shouldBe NoteKind.DECISION
        }

        test("huge body without terminator yields no token and is fast") {
            val body = "A".repeat(65_536)
            table.prefixToken(body) shouldBe null
            val ms = measureTimeMillis { repeat(1_000) { table.expectedKindFor(body) } }
            (ms < 2_000) shouldBe true
        }

        test("scan window boundary") {
            val inside = "A".repeat(NotePrefixKinds.MAX_SCAN_CHARS - 1) + ":" + "rest"
            table.prefixToken(inside) shouldBe "A".repeat(NotePrefixKinds.MAX_SCAN_CHARS - 1)
            val outside = "A".repeat(NotePrefixKinds.MAX_SCAN_CHARS) + ": rest"
            table.prefixToken(outside) shouldBe null
            // a body that is exactly the window and a complete token is still a token
            table.prefixToken("A".repeat(NotePrefixKinds.MAX_SCAN_CHARS)) shouldBe "A".repeat(NotePrefixKinds.MAX_SCAN_CHARS)
        }

        test("keys over the length limit and blank keys are dropped") {
            val long = "k".repeat(NotePrefixKinds.MAX_PREFIX_LENGTH + 1)
            val t = NotePrefixKinds(mapOf(long to NoteKind.RISK, "  " to NoteKind.RISK, "ok" to NoteKind.INFO))
            t.expectedKindFor("$long: x") shouldBe null
            t.expectedKindFor("ok: x")?.second shouldBe NoteKind.INFO
        }

        test("table is capped at the entry limit") {
            val big = (0 until NotePrefixKinds.MAX_ENTRIES + 10).associate { "p$it" to NoteKind.INFO }
            val t = NotePrefixKinds(big)
            t.expectedKindFor("p0: x")?.second shouldBe NoteKind.INFO
            t.expectedKindFor("p${NotePrefixKinds.MAX_ENTRIES + 5}: x") shouldBe null
        }
    })
