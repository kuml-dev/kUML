package dev.kuml.workspace.latex

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class LatexEscaperTest :
    FunSpec({
        fun t(raw: String) = LatexEscaper.text(raw = raw)

        test("the ten special characters and the characters that open optional arguments or ligatures") {
            t("\\") shouldBe "\\textbackslash{}"
            t("{}") shouldBe "\\{\\}"
            t("$&#%_") shouldBe "\\$\\&\\#\\%\\_"
            t("~^") shouldBe "\\textasciitilde{}\\textasciicircum{}"
            t("\"") shouldBe "\\textquotedbl{}"
            t("[x]") shouldBe "{[}x{]}"
            t("a<b>c|d") shouldBe "a\\textless{}b\\textgreater{}c\\textbar{}d"
            t("`") shouldBe "\\textasciigrave{}"
        }

        test("typographic characters map to text macros") {
            t("ä ö ü ß Ä") shouldBe "ä ö ü ß Ä"
            t("„a“ ‚b‘ « » – — …") shouldBe
                "\\quotedblbase{}a\\textquotedblleft{} \\quotesinglbase{}b\\textquoteleft{} \\guillemotleft{} \\guillemotright{} -- --- \\ldots{}"
            t("→ ⇒ ✓ €") shouldBe "\\textrightarrow{} $\\Rightarrow$ $\\checkmark$ \\texteuro{}"
            t("a\u00A0b") shouldBe "a~b"
        }

        test("characters pdflatex cannot typeset become a visible placeholder and are reported") {
            val unknown = LatexEscaper.Unknown()
            val out = LatexEscaper.text(raw = "x \uD83D\uDE00 日本 Привет", unknown = unknown)
            out shouldContain "\\kumlglyph{U+1F600}"
            out shouldContain "\\kumlglyph{U+65E5}"
            out shouldNotContain "\uD83D"
            unknown.codePoints shouldContain 0x1F600
        }

        test("control characters, bidi overrides and zero-width characters are removed") {
            t("a\u202Eb\u200Bc\u0000d\u001Be\u2028f\u0085g") shouldBe "abcde fg"
            t("line1\nline2\tx\r") shouldBe "line1 line2 x "
        }

        test("a TeX hex-escape sequence stays harmless text") {
            t("^^5c") shouldBe "\\textasciicircum{}\\textasciicircum{}5c"
        }

        test("code keeps spaces visible and blocks ligatures") {
            LatexEscaper.code(raw = "a  -- b<<c 'q' `x`") shouldBe
                "a\\ \\ -{}-{}\\ b\\textless{}\\textless{}c\\ \\textquotesingle{}q\\textquotesingle{}\\ \\textasciigrave{}x\\textasciigrave{}"
        }

        test("url: only http, https and mailto") {
            listOf(
                "javascript:alert(1)",
                "file:///etc/passwd",
                "run:calc",
                "launch:x",
                "ftp://x/y",
                "data:text/html,x",
                "//x/y",
                "x",
                " JAVASCRIPT:1",
            ).forEach { LatexEscaper.url(it).shouldBeNull() }
            LatexEscaper.url("https://example.org/a_b?x=1&y=2") shouldBe "https://example.org/a_b?x=1&y=2"
            LatexEscaper.url("mailto:a@b.de") shouldBe "mailto:a@b.de"
        }

        test("url: nothing that TeX could read as a command or group survives") {
            val out = LatexEscaper.url("https://x.org/a}b\\c{d%e#f g\"h")!!
            out shouldBe "https://x.org/a\\%7Db\\%5Cc\\%7Bd\\%25e\\#f\\%20g\\%22h"
            out.none { it == '{' || it == '}' } shouldBe true
            LatexEscaper.url("https://x.org/a%20b") shouldBe "https://x.org/a\\%20b"
            LatexEscaper.url("https://x.org/ä") shouldBe "https://x.org/\\%C3\\%A4"
        }

        test("commentSafe leaves no line break and no TeX special character") {
            val out = LatexEscaper.commentSafe("a\nb\\c{d}%e")
            out shouldBe "a b?c?d??e"
        }

        test("Latin range 0xC0..0x17F: undefined code points go to the glyph placeholder, all others are typeset") {
            val undefined = setOf(0x0126, 0x0127, 0x0138, 0x013F, 0x0140, 0x0149, 0x0166, 0x0167, 0x017F)
            for (cp in 0x00C0..0x017F) {
                val out = t(String(Character.toChars(cp)))
                if (cp in undefined) {
                    out shouldBe "\\kumlglyph{U+%04X}".format(java.util.Locale.ROOT, cp)
                } else {
                    out shouldNotContain "kumlglyph"
                }
            }
        }
    })
