package dev.kuml.widget.compose

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import dev.kuml.core.ocl.OclSyntax

/**
 * Builds a syntax-colored [AnnotatedString] over [text] using [OclSyntax.highlight],
 * optionally underlining [errorRange] (an inclusive-last `IntRange` as produced by
 * [dev.kuml.core.ocl.OclCheckResult.Error], i.e. `a until b` semantics: `range.last == b - 1`)
 * in the error color. Never throws; blank input yields an empty [AnnotatedString].
 */
internal fun highlightOcl(
    text: String,
    colors: OclHighlightColors,
    errorRange: IntRange?,
): AnnotatedString =
    buildAnnotatedString {
        append(text)
        for (tok in OclSyntax.highlight(text)) {
            val start = tok.start.coerceIn(0, text.length)
            val end = tok.end.coerceIn(start, text.length)
            if (end > start) addStyle(SpanStyle(color = colors.colorFor(tok.kind)), start, end)
        }
        if (errorRange != null) {
            val start = errorRange.first.coerceIn(0, text.length)
            // errorRange is inclusive-last (from `a until b`) -> exclusive end is `last + 1`.
            val end = (errorRange.last + 1).coerceIn(start, text.length)
            if (end > start) {
                addStyle(
                    SpanStyle(color = colors.errorText, textDecoration = TextDecoration.Underline),
                    start,
                    end,
                )
            }
        }
    }

/**
 * [VisualTransformation] that colorizes an OCL expression via [highlightOcl].
 * Offsets are never remapped — the transformed text has the exact same length
 * and character positions as the input, so [OffsetMapping.Identity] is always
 * correct and cursor placement stays exact.
 */
internal class OclHighlightTransformation(
    private val colors: OclHighlightColors,
    private val errorRange: IntRange?,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlightOcl(text.text, colors, errorRange), OffsetMapping.Identity)
}
