package dev.kuml.widget.compose

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.style.TextDecoration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Pure logic tests for [highlightOcl] / [OclHighlightTransformation] — no
 * Compose UI runtime involved, only [AnnotatedString] inspection. This is the
 * primary Wave-4 unit-test target; the `@Composable` [OclGuardEditor] body,
 * debounce timing, and rendered button behavior are deferred to Wave 6's
 * Compose UI/robot tests.
 */
class OclHighlightTransformationTest :
    FunSpec(body = {
        val colors = OclHighlightColors.Default

        fun spanAt(
            annotated: AnnotatedString,
            start: Int,
            end: Int,
        ): SpanStyle? =
            annotated.spanStyles
                .firstOrNull { it.start == start && it.end == end }
                ?.item

        test(name = "blank input => empty result, no spans") {
            val result = highlightOcl("", colors, null)
            result.text shouldBe ""
            result.spanStyles.shouldBeEmpty()
        }

        test(name = "text is never mutated by the transformation") {
            val text = "self.count > 0"
            val transformed = OclHighlightTransformation(colors, null).filter(AnnotatedString(text))
            transformed.text.text shouldBe text
        }

        test(name = "offset mapping is identity") {
            val text = "self.count > 0"
            val transformed = OclHighlightTransformation(colors, null).filter(AnnotatedString(text))
            transformed.offsetMapping shouldBe OffsetMapping.Identity
            for (k in listOf(0, 1, 4, text.length / 2, text.length)) {
                transformed.offsetMapping.originalToTransformed(k) shouldBe k
                transformed.offsetMapping.transformedToOriginal(k) shouldBe k
            }
        }

        test(name = "keyword and literal coloring on an if-expression") {
            val text = "if true then 1 else 0 endif"
            val result = highlightOcl(text, colors, null)

            val ifStart = text.indexOf("if")
            val thenStart = text.indexOf("then")
            val elseStart = text.indexOf("else")
            val endifStart = text.indexOf("endif")

            spanAt(result, ifStart, ifStart + 2)?.color shouldBe colors.keyword
            spanAt(result, thenStart, thenStart + 4)?.color shouldBe colors.keyword
            spanAt(result, elseStart, elseStart + 4)?.color shouldBe colors.keyword
            spanAt(result, endifStart, endifStart + 5)?.color shouldBe colors.keyword

            val oneStart = text.indexOf(" 1 ") + 1
            spanAt(result, oneStart, oneStart + 1)?.color shouldBe colors.literal
        }

        test(name = "operator, paren, and ident coloring on '(a > b)'") {
            val text = "(a > b)"
            val result = highlightOcl(text, colors, null)

            spanAt(result, 0, 1)?.color shouldBe colors.paren // (
            spanAt(result, 1, 2)?.color shouldBe colors.ident // a
            spanAt(result, 3, 4)?.color shouldBe colors.operator // >
            spanAt(result, 5, 6)?.color shouldBe colors.ident // b
            spanAt(result, 6, 7)?.color shouldBe colors.paren // )
        }

        test(name = "string/int/bool literal coloring") {
            val strResult = highlightOcl("'x'", colors, null)
            spanAt(strResult, 0, 3)?.color shouldBe colors.literal

            val intResult = highlightOcl("42", colors, null)
            spanAt(intResult, 0, 2)?.color shouldBe colors.literal

            val boolResult = highlightOcl("true", colors, null)
            spanAt(boolResult, 0, 4)?.color shouldBe colors.literal
        }

        test(name = "unterminated string surfaces an ERROR span") {
            val result = highlightOcl("'oops", colors, null)
            result.spanStyles.any { it.item.color == colors.error } shouldBe true
        }

        test(name = "error-range underline: inclusive-last -> exclusive-end conversion") {
            val result = highlightOcl("ab", colors, 0..1)
            val underline =
                result.spanStyles.firstOrNull {
                    it.item.textDecoration == TextDecoration.Underline
                }
            checkNotNull(underline) { "expected an underline span" }
            underline.start shouldBe 0
            underline.end shouldBe 2
            underline.item.color shouldBe colors.errorText
        }

        test(name = "out-of-bounds error range is clamped, not thrown") {
            val result = highlightOcl("ab", colors, 5..9)
            result.spanStyles.none { it.item.textDecoration == TextDecoration.Underline } shouldBe true
        }

        test(name = "null error range => no underline span present") {
            val result = highlightOcl("ab", colors, null)
            result.spanStyles.none { it.item.textDecoration == TextDecoration.Underline } shouldBe true
        }
    })
