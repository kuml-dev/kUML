package dev.kuml.widget.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.kuml.core.ocl.OclTokenKind

/**
 * Fixed color palette used to syntax-color an OCL expression in
 * [OclGuardEditor]. Deliberately plain [Color] constants rather than
 * `MaterialTheme`-derived values — keeps the palette directly usable from
 * tests and self-contained for this wave; theming from `MaterialTheme` is a
 * trivial later polish.
 */
@Immutable
internal data class OclHighlightColors(
    val keyword: Color,
    val ident: Color,
    val literal: Color,
    val operator: Color,
    val paren: Color,
    val error: Color,
    val errorText: Color,
) {
    /** Maps a lexical [kind] to the color it should be rendered with. */
    fun colorFor(kind: OclTokenKind): Color =
        when (kind) {
            OclTokenKind.KEYWORD -> keyword
            OclTokenKind.IDENT -> ident
            OclTokenKind.LITERAL -> literal
            OclTokenKind.OPERATOR -> operator
            OclTokenKind.PAREN -> paren
            OclTokenKind.ERROR -> error
        }

    companion object {
        val Default =
            OclHighlightColors(
                keyword = Color(0xFF7C4DFF),
                ident = Color(0xFF1B1B1B),
                literal = Color(0xFF1565C0),
                operator = Color(0xFF00695C),
                paren = Color(0xFF616161),
                error = Color(0xFFC62828),
                errorText = Color(0xFFC62828),
            )
    }
}
