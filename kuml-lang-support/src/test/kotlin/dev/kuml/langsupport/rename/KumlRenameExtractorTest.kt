package dev.kuml.langsupport.rename

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class KumlRenameExtractorTest :
    FunSpec({

        // ── Basic string literal detection ────────────────────────────────────

        test("finds string literal in classOf(name = \"Order\")") {
            val text = """classOf(name = "Order") { }"""
            val candidates = KumlRenameExtractor.findRenameCandidates(text, "Order")

            candidates shouldHaveSize 1
            val c = candidates.first()
            c.kind shouldBe KumlRenameExtractor.Kind.STRING_LITERAL
            // offset must point to the 'O' of Order, not the opening quote
            text[c.offset] shouldBe 'O'
            text.substring(c.offset, c.endOffset) shouldBe "Order"
        }

        test("finds positional string literal in partDef(\"Vehicle\")") {
            val text = """partDef("Vehicle") { }"""
            val candidates = KumlRenameExtractor.findRenameCandidates(text, "Vehicle")

            candidates shouldHaveSize 1
            val c = candidates.first()
            c.kind shouldBe KumlRenameExtractor.Kind.STRING_LITERAL
            text.substring(c.offset, c.endOffset) shouldBe "Vehicle"
        }

        test("finds multiple occurrences — two \"Order\" in one script") {
            val text =
                """
                classOf(name = "Order") { }
                association(source = "Order", target = "Item")
                """.trimIndent()
            val candidates = KumlRenameExtractor.findRenameCandidates(text, "Order")

            // Both string-literal occurrences should be found
            val literals = candidates.filter { it.kind == KumlRenameExtractor.Kind.STRING_LITERAL }
            literals.size shouldBe 2
        }

        // ── Boundary / substring exclusion ───────────────────────────────────

        test("does not match substring of longer identifier — OrderItem vs Order") {
            // "OrderItem" as a variable reference must not match when searching for "Order"
            val text =
                """
                val OrderItem = classOf(name = "Order")
                """.trimIndent()
            val candidates = KumlRenameExtractor.findRenameCandidates(text, "Order")

            // Only the string literal "Order" should match; not the prefix of OrderItem
            val varRefs = candidates.filter { it.kind == KumlRenameExtractor.Kind.VARIABLE_REF }
            varRefs.forEach { c ->
                // Make sure the character after the match is not a word character
                val afterEnd = c.endOffset
                if (afterEnd < text.length) {
                    val charAfter = text[afterEnd]
                    val isWordChar = charAfter.isLetterOrDigit() || charAfter == '_'
                    isWordChar shouldBe false
                }
            }
        }

        // ── Comment masking ───────────────────────────────────────────────────

        test("ignores matches in line comments") {
            val text =
                """
                // classOf(name = "Order") — this is a comment
                classOf(name = "User") { }
                """.trimIndent()
            val candidates = KumlRenameExtractor.findRenameCandidates(text, "Order")

            candidates.shouldBeEmpty()
        }

        test("ignores matches in block comments") {
            val text =
                """
                /* classOf(name = "Order") is the old name */
                classOf(name = "NewName") { }
                """.trimIndent()
            val candidates = KumlRenameExtractor.findRenameCandidates(text, "Order")

            candidates.shouldBeEmpty()
        }

        // ── Offset precision ──────────────────────────────────────────────────

        test("string literal offset points to name without quotes") {
            val text = """partDef("Engine")"""
            val candidates = KumlRenameExtractor.findRenameCandidates(text, "Engine")

            candidates shouldHaveSize 1
            val c = candidates.first()
            // Must not start at the quote character
            text[c.offset] shouldBe 'E'
            text[c.offset - 1] shouldBe '"'
        }

        // ── Empty/blank guards ────────────────────────────────────────────────

        test("returns empty list for blank text") {
            KumlRenameExtractor.findRenameCandidates("   ", "Order").shouldBeEmpty()
        }

        test("returns empty list for blank name") {
            KumlRenameExtractor.findRenameCandidates("""classOf(name = "Order")""", "").shouldBeEmpty()
        }

        // ── Ordering ──────────────────────────────────────────────────────────

        test("results are sorted by ascending offset") {
            val text =
                """
                classOf(name = "Order") { }
                interfaceOf(name = "Order") { }
                """.trimIndent()
            val candidates = KumlRenameExtractor.findRenameCandidates(text, "Order")

            val offsets = candidates.map { it.offset }
            offsets shouldBe offsets.sorted()
        }

        // ── maskComments ──────────────────────────────────────────────────────

        test("maskComments preserves string length") {
            val original = "val x = 1 // comment\nval y = 2"
            val masked = KumlRenameExtractor.maskComments(original)
            masked.length shouldBe original.length
        }

        test("maskComments replaces line comment content with spaces") {
            val original = "code // secret\nnext"
            val masked = KumlRenameExtractor.maskComments(original)
            // "// secret" should become "         " (9 spaces), newline preserved
            masked[masked.indexOf('\n') - 1] shouldBe ' '
            masked.contains("secret") shouldBe false
        }

        test("maskComments replaces block comment content with spaces") {
            val original = "before /* hidden */ after"
            val masked = KumlRenameExtractor.maskComments(original)
            masked.length shouldBe original.length
            masked.contains("hidden") shouldBe false
            masked.contains("before") shouldBe true
            masked.contains("after") shouldBe true
        }
    })
