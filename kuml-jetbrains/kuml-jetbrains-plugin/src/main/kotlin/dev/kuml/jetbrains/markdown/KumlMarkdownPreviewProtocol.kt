package dev.kuml.jetbrains.markdown

import java.util.Base64

/**
 * Wire format for the JCEF <-> JVM round trip between the bundled bridge script
 * ([KumlMarkdownPreviewExtension], resource `/kuml/kuml-markdown-preview.js`) and
 * [KumlMarkdownPreviewExtension] itself.
 *
 * Deliberately **not JSON**: neither Gson nor Jackson is a dependency this module can
 * rely on being present on every supported IDE's classpath, and kotlinx-serialization
 * is excluded from this module for classloader reasons (see `build.gradle.kts`).
 * Instead, each message is three fields delimited by the ASCII Unit Separator
 * (0x1F, ``), with the payload itself Base64-encoded (UTF-8) so newlines, `<`,
 * `&`, quotes and multi-KB SVG content all round-trip safely regardless of transport
 * quoting.
 */
internal object KumlMarkdownPreviewProtocol {
    /** Browser -> IDE: "please render fence #ordinal". */
    const val REQUEST_EVENT: String = "kuml.markdown.render.request"

    /** IDE -> Browser: "here is the rendered HTML for fence #ordinal". */
    const val RESPONSE_EVENT: String = "kuml.markdown.render.response"

    /** ASCII Unit Separator — never appears in a requestId, ordinal, or Base64 alphabet. */
    private const val SEP: Char = '\u001F'

    data class Request(
        val requestId: String,
        val ordinal: Int,
        val fallbackSource: String,
    )

    /**
     * Decodes a browser-sent request payload: `"<id><SEP><ordinal><SEP><base64(source)>"`.
     * Returns `null` on any malformed payload rather than throwing — the bridge script
     * is untrusted-ish browser-side JS and must never be able to crash the IDE process.
     */
    fun decodeRequest(payload: String): Request? {
        val parts = payload.split(SEP)
        if (parts.size != 3) return null
        val requestId = parts[0]
        val ordinal = parts[1].toIntOrNull() ?: return null
        val fallbackSource =
            try {
                decode(parts[2])
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (requestId.isEmpty()) return null
        return Request(requestId, ordinal, fallbackSource)
    }

    /** Encodes an IDE-sent response payload: `"<id><SEP><ordinal><SEP><base64(html)>"`. */
    fun encodeResponse(
        requestId: String,
        ordinal: Int,
        html: String,
    ): String = listOf(requestId, ordinal.toString(), encode(html)).joinToString(SEP.toString())

    private fun encode(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun decode(base64: String): String = String(Base64.getDecoder().decode(base64), Charsets.UTF_8)
}
