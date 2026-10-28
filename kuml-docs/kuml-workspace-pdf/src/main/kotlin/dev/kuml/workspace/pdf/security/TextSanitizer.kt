package dev.kuml.workspace.pdf.security

import java.text.Normalizer

/** Cleans untrusted text before it reaches the PDF: no control characters, NFC, bounded length. */
internal object TextSanitizer {
    fun clean(
        text: String,
        maxLength: Int = 20_000,
    ): String {
        val sb = StringBuilder(minOf(text.length, maxLength))
        for (ch in text) {
            if (sb.length >= maxLength) break
            if (ch == '\n' || ch == '\t' || !Character.isISOControl(ch)) sb.append(ch)
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFC)
    }
}
