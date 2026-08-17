package dev.kuml.jetbrains.asciidoc

import dev.kuml.jetbrains.KumlPreviewRenderer
import dev.kuml.jetbrains.preview.KumlDocPreviewCache
import dev.kuml.jetbrains.preview.KumlPreviewHtml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure HTML rewrite pipeline for AsciiDoc preview.
 *
 * Takes the HTML Asciidoctor produced for an `.adoc` document and replaces
 * kUML listing / unresolved-macro fragments with rendered diagram containers.
 * Never throws — on any unexpected error the original [html] is returned unchanged.
 */
internal object KumlAsciidocHtmlRewriter {
    /**
     * Rewrites [html] produced for [adocSource], resolving macro paths relative to [baseDir].
     *
     * @param projectBaseDir optional project content root used by the path guard
     * @param onResolvedMacroPaths optional callback with successfully resolved macro file paths
     *        (used by the file watcher to refresh the preview when those files change)
     */
    fun rewrite(
        html: String,
        adocSource: String,
        baseDir: Path,
        projectBaseDir: Path? = null,
        onResolvedMacroPaths: ((Set<Path>) -> Unit)? = null,
    ): String =
        try {
            rewriteInternal(html, adocSource, baseDir, projectBaseDir, onResolvedMacroPaths)
        } catch (_: Throwable) {
            html
        }

    private fun rewriteInternal(
        html: String,
        adocSource: String,
        baseDir: Path,
        projectBaseDir: Path?,
        onResolvedMacroPaths: ((Set<Path>) -> Unit)?,
    ): String {
        val blocks = KumlAsciidocBlockParser.parse(adocSource)
        if (blocks.isEmpty()) {
            onResolvedMacroPaths?.invoke(emptySet())
            return html
        }

        var result = html
        val resolvedMacroPaths = linkedSetOf<Path>()

        for (block in blocks) {
            when (block.kind) {
                KumlAsciidocBlock.Kind.LISTING -> {
                    val replacement = renderListing(block)
                    result = replaceListingFragment(result, block.source, replacement) ?: result
                }
                KumlAsciidocBlock.Kind.BLOCK_MACRO -> {
                    val target = block.targetPath.orEmpty()
                    val name = KumlAsciidocBlockParser.resolveName(block.attributes, defaultName = target.ifBlank { "macro" })
                    val theme = KumlAsciidocBlockParser.resolveTheme(block.attributes)
                    val width = KumlAsciidocBlockParser.resolveWidth(block.attributes)

                    val guard = KumlAsciidocPathGuard.resolve(target, baseDir, projectBaseDir)
                    val replacement =
                        when (guard) {
                            is KumlAsciidocPathGuard.Result.Rejected -> {
                                KumlPreviewHtml.buildErrorContainer(guard.reason, name)
                            }
                            is KumlAsciidocPathGuard.Result.Ok -> {
                                resolvedMacroPaths.add(guard.resolvedPath)
                                readAndRender(guard.resolvedPath, name, theme, width)
                            }
                        }
                    result = replaceMacroFragment(result, target, replacement) ?: result
                }
            }
        }

        onResolvedMacroPaths?.invoke(resolvedMacroPaths)
        return result
    }

    private fun renderListing(block: KumlAsciidocBlock): String {
        val name = KumlAsciidocBlockParser.resolveName(block.attributes)
        val theme = KumlAsciidocBlockParser.resolveTheme(block.attributes)
        val width = KumlAsciidocBlockParser.resolveWidth(block.attributes)
        return outcomeToHtml(
            KumlDocPreviewCache.getOrRender(block.source, theme, name),
            name,
            theme,
            width,
        )
    }

    private fun readAndRender(
        path: Path,
        name: String,
        theme: String,
        width: String?,
    ): String {
        val text =
            try {
                if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                    return KumlPreviewHtml.buildErrorContainer(
                        "Datei nicht gefunden oder nicht lesbar: $path",
                        name,
                    )
                }
                Files.readString(path)
            } catch (e: Exception) {
                return KumlPreviewHtml.buildErrorContainer(
                    "Datei nicht lesbar: $path (${e.message ?: e.javaClass.simpleName})",
                    name,
                )
            }
        return outcomeToHtml(
            KumlDocPreviewCache.getOrRender(text, theme, name),
            name,
            theme,
            width,
        )
    }

    private fun outcomeToHtml(
        outcome: KumlPreviewRenderer.Outcome,
        name: String,
        theme: String,
        width: String?,
    ): String =
        when (outcome) {
            is KumlPreviewRenderer.Outcome.Svg -> {
                KumlPreviewHtml.buildSvgContainer(
                    KumlPreviewHtml.sanitizeSvg(outcome.svg),
                    name,
                    theme,
                    width,
                )
            }
            is KumlPreviewRenderer.Outcome.Failure -> {
                KumlPreviewHtml.buildErrorContainer(outcome.message, name)
            }
            is KumlPreviewRenderer.Outcome.Empty -> {
                KumlPreviewHtml.buildEmptyContainer(name)
            }
        }

    /**
     * Escapes source the same way Asciidoctor typically does inside listing HTML.
     *
     * Kept as a stable public helper (used by tests and as a fallback needle variant
     * in [replaceMacroFragment]) even though [replaceListingFragment] itself now matches
     * via entity-decoding (see [decodeEntities]), which subsumes this escaping scheme.
     */
    internal fun escapeForHtmlMatch(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    // ---------------------------------------------------------------------
    // Entity-decoded matching
    //
    // Different AsciiDoc syntax highlighters (CodeRay, Rouge/Pygments, the plain
    // "escape only & < >" fallback) each encode the *same* source text differently
    // inside the generated HTML. Rather than guess every highlighter's encoding up
    // front, we decode a normalized copy of the haystack back towards plain text and
    // search for the (already plain-text) source inside that copy — then translate the
    // hit position back into the ORIGINAL, untouched HTML via an offset map.
    //
    // Security note: decoding exists solely to compute match offsets. The decoded
    // string is never returned, never concatenated into output, and never passed to
    // KumlPreviewHtml. sanitizeSvg/escapeHtml/escapeHtmlAttribute and the path guard
    // are untouched by any of this.
    // ---------------------------------------------------------------------

    /** Entity-decoded copy of an HTML string plus per-character offsets into the ORIGINAL string. */
    private class DecodedHtml(
        val text: String,
        // startOffsets[i] = index in original of first char producing decoded[i]
        val startOffsets: IntArray,
        // endOffsets[i] = index in original just past decoded[i]'s source chars
        val endOffsets: IntArray,
    )

    private val NAMED_ENTITIES: Map<String, String> =
        mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            // Normalized to a plain space (not U+00A0) so matching stays stable
            // against source text that only ever contains ordinary spaces.
            "nbsp" to " ",
            "lsquo" to "‘",
            "rsquo" to "’",
            "ldquo" to "“",
            "rdquo" to "”",
            "ndash" to "–",
            "mdash" to "—",
            "hellip" to "…",
            "bull" to "•",
            "sol" to "/",
            "num" to "#",
        )

    /**
     * Decodes HTML entities in [html] into a normalized plain-text copy, tracking exactly
     * which original character range produced each decoded character. Never throws — any
     * malformed entity is emitted literally (`&` is appended as-is, one original char, one
     * decoded char) rather than dropped or causing a failure.
     *
     * When [stripTags] is true, well-formed tags (`<name …>`, `</name>`, `<!-- … -->`) are
     * skipped entirely and emit no decoded characters — used to see through token-highlighter
     * markup like `<span class="s">"X"</span>` for tier D matching.
     */
    private fun decodeEntities(
        html: String,
        stripTags: Boolean = false,
    ): DecodedHtml {
        val sb = StringBuilder(html.length)
        val starts = ArrayList<Int>(html.length)
        val ends = ArrayList<Int>(html.length)

        fun emit(
            c: Char,
            start: Int,
            end: Int,
        ) {
            sb.append(c)
            starts.add(start)
            ends.add(end)
        }

        var i = 0
        val n = html.length
        while (i < n) {
            val c = html[i]

            if (stripTags && c == '<') {
                val next = if (i + 1 < n) html[i + 1] else ' '
                val looksLikeTag = next.isLetter() || next == '/' || next == '!'
                val gt = if (looksLikeTag) html.indexOf('>', i) else -1
                if (gt > i) {
                    i = gt + 1
                    continue
                }
                emit(c, i, i + 1)
                i++
                continue
            }

            if (c == '&') {
                val semi = html.indexOf(';', i + 1)
                var handled = false
                if (semi in (i + 1)..(i + 12)) {
                    val body = html.substring(i + 1, semi)
                    if (body.startsWith("#")) {
                        val codePoint =
                            try {
                                if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
                                    body.substring(2).toInt(16)
                                } else {
                                    body.substring(1).toInt(10)
                                }
                            } catch (_: NumberFormatException) {
                                -1
                            }
                        val isValidCodePoint =
                            codePoint in 0..0x10FFFF && codePoint !in 0xD800..0xDFFF
                        if (isValidCodePoint) {
                            when {
                                codePoint == 0xA0 -> emit(' ', i, semi + 1)
                                codePoint > 0xFFFF -> {
                                    val chars = Character.toChars(codePoint)
                                    for (ch in chars) emit(ch, i, semi + 1)
                                }
                                else -> emit(codePoint.toChar(), i, semi + 1)
                            }
                            handled = true
                        }
                    } else {
                        val mapped = NAMED_ENTITIES[body]
                        if (mapped != null) {
                            for (ch in mapped) emit(ch, i, semi + 1)
                            handled = true
                        }
                    }
                }
                if (handled) {
                    i = semi + 1
                    continue
                }
                emit('&', i, i + 1)
                i++
                continue
            }

            emit(c, i, i + 1)
            i++
        }
        return DecodedHtml(sb.toString(), starts.toIntArray(), ends.toIntArray())
    }

    /**
     * Returns (indexInDecodedText, startInOriginal, endExclusiveInOriginal) of the first
     * occurrence of [needle] inside [decoded] at or after [fromIndex], or null. The decoded
     * index is returned alongside the original-string offsets so callers can resume searching
     * for a *later* occurrence (`fromIndex = decodedIndex + 1`) when the first hit turns out
     * not to sit inside a usable container — see [replaceListingFragment].
     */
    private fun findInDecoded(
        decoded: DecodedHtml,
        needle: String,
        fromIndex: Int = 0,
    ): Triple<Int, Int, Int>? {
        if (needle.isEmpty()) return null
        val i = decoded.text.indexOf(needle, fromIndex)
        if (i < 0) return null
        return Triple(i, decoded.startOffsets[i], decoded.endOffsets[i + needle.length - 1])
    }

    /** Collapses runs of whitespace to a single space and trims, keeping an index map back to [text]. */
    private fun collapseWhitespaceWithIndex(text: String): Pair<String, IntArray> {
        val sb = StringBuilder(text.length)
        val map = ArrayList<Int>(text.length)
        var lastWasSpace = true // treat leading run as already-collapsed (trims it)
        for (i in text.indices) {
            val c = text[i]
            if (c.isWhitespace()) {
                if (!lastWasSpace) {
                    sb.append(' ')
                    map.add(i)
                    lastWasSpace = true
                }
            } else {
                sb.append(c)
                map.add(i)
                lastWasSpace = false
            }
        }
        var result = sb.toString()
        var indexMap = map
        if (result.endsWith(" ")) {
            result = result.substring(0, result.length - 1)
            indexMap = ArrayList(indexMap.subList(0, indexMap.size - 1))
        }
        return result to indexMap.toIntArray()
    }

    /**
     * Finds and replaces the enclosing listing-block HTML that contains [source].
     * Returns null when no tier matches (no-op contract preserved for callers).
     */
    internal fun replaceListingFragment(
        html: String,
        source: String,
        replacement: String,
    ): String? {
        val sourceLf = source.replace("\r\n", "\n").replace('\r', '\n')

        if (sourceLf.isNotEmpty()) {
            val decoded = decodeEntities(html)

            // Tier B: entity-decoded match. Subsumes the legacy "escape & < >" scheme
            // (when only those three are escaped, decoding reproduces the raw source)
            // and additionally fixes highlighters (e.g. CodeRay) that also emit &quot;.
            //
            // A single document can contain several kUML listings, and blocks are rewritten
            // sequentially into `html`/`result` — so by the time a later block is processed,
            // `html` already contains EARLIER blocks' rendered replacements (SVG text content,
            // or an error container's escaped message). If this block's source text happens to
            // also appear literally inside one of those, the FIRST occurrence in `html` is not
            // this block's own listing and [findEnclosingListingElement] correctly rejects it
            // (no listingblock/pre/code ancestor, or — see the "kuml-diagram-" guard there — it
            // sits inside previously-generated preview markup). Giving up at that point would
            // either silently skip this block entirely or, worse, splice its replacement into
            // the unrelated container that happened to match. Instead, keep scanning forward
            // for the NEXT occurrence until one resolves to a genuine container, or none remain.
            run {
                var searchFrom = 0
                while (true) {
                    val (decodedIdx, start, end) = findInDecoded(decoded, sourceLf, searchFrom) ?: break
                    findEnclosingListingElement(html, start, end)?.let {
                        return html.replaceRange(it.first, it.second, replacement)
                    }
                    searchFrom = decodedIdx + 1
                }
            }

            // Tier C: whitespace-collapsed match — covers highlighters that reflow
            // indentation/line-wrapping around the source text. Same retry-on-rejection
            // reasoning as Tier B above.
            val (collapsedNeedle, _) = collapseWhitespaceWithIndex(sourceLf)
            if (collapsedNeedle.isNotEmpty()) {
                val (collapsedHay, hayMap) = collapseWhitespaceWithIndex(decoded.text)
                if (hayMap.isNotEmpty()) {
                    var searchFrom = 0
                    while (true) {
                        val idx = collapsedHay.indexOf(collapsedNeedle, searchFrom)
                        if (idx < 0) break
                        val decodedStart = hayMap[idx]
                        val decodedEndInclusive = hayMap[idx + collapsedNeedle.length - 1]
                        val start = decoded.startOffsets[decodedStart]
                        val end = decoded.endOffsets[decodedEndInclusive]
                        findEnclosingListingElement(html, start, end)?.let {
                            return html.replaceRange(it.first, it.second, replacement)
                        }
                        searchFrom = idx + 1
                    }
                }
            }

            // Tier D: tag-stripped, entity-decoded match — covers token-highlighting
            // output (e.g. Rouge/Pygments spans wrapping individual tokens). Same
            // retry-on-rejection reasoning as Tier B above.
            run {
                val decodedStripped = decodeEntities(html, stripTags = true)
                var searchFrom = 0
                while (true) {
                    val (decodedIdx, start, end) = findInDecoded(decodedStripped, sourceLf, searchFrom) ?: break
                    findEnclosingListingElement(html, start, end)?.let {
                        return html.replaceRange(it.first, it.second, replacement)
                    }
                    searchFrom = decodedIdx + 1
                }
            }
        }

        // Tier E (existing, unchanged): empty source → match language-kuml markers.
        if (source.isBlank()) {
            val langMarker =
                Regex(
                    """<div\b[^>]*class="[^"]*listingblock[^"]*"[^>]*>[\s\S]*?(?:data-lang\s*=\s*"kuml"|class="[^"]*language-kuml[^"]*")[\s\S]*?</div>\s*</div>""",
                    RegexOption.IGNORE_CASE,
                )
            langMarker.find(html)?.let { m ->
                return html.replaceRange(m.range.first, m.range.last + 1, replacement)
            }
        }
        return null
    }

    /**
     * Finds leftover unresolved-macro HTML (literal `kuml::path`) and replaces it.
     */
    internal fun replaceMacroFragment(
        html: String,
        targetPath: String,
        replacement: String,
    ): String? {
        val needle = "kuml::$targetPath"

        // Primary: entity-decoded match (handles any escaping scheme uniformly).
        val decoded = decodeEntities(html)
        findInDecoded(decoded, needle)?.let { (_, start, end) ->
            val range = findEnclosingMacroElement(html, start, end)
            return html.replaceRange(range.first, range.second, replacement)
        }

        // Fallback: raw indexOf across the legacy needle variants, so no currently-passing
        // macro test can regress even if a haystack somehow evades entity decoding.
        val needleVariants =
            listOf(
                needle,
                "kuml::" + escapeForHtmlMatch(targetPath),
                escapeForHtmlMatch(needle),
            ).distinct()

        for (variant in needleVariants) {
            val idx = html.indexOf(variant)
            if (idx < 0) continue
            val range = findEnclosingMacroElement(html, idx, idx + variant.length)
            return html.replaceRange(range.first, range.second, replacement)
        }
        return null
    }

    // ---------------------------------------------------------------------
    // Ancestor-chain based element selection
    // ---------------------------------------------------------------------

    private data class Ancestor(
        val openStart: Int,
        val openEnd: Int,
        val closeEnd: Int,
        val tagName: String,
        val classAttr: String,
    )

    private val CANDIDATE_TAG_NAMES = setOf("div", "pre", "code", "p", "td", "section", "article", "blockquote")

    /**
     * Walks backwards from [contentStart] collecting HTML ancestors that genuinely CONTAIN
     * `[contentStart, contentEnd)` — i.e. whose matching close tag ends at or after [contentEnd].
     * Ordered nearest-ancestor-first. Bounded by [maxLevels] and [maxScanBack] to keep this a
     * cheap heuristic scan, not a full parse. Short-circuits once a `listingblock`-classed
     * ancestor is recorded, since that is always the outermost element of interest here.
     */
    private fun ancestorChain(
        html: String,
        contentStart: Int,
        contentEnd: Int,
        maxLevels: Int = 8,
        maxScanBack: Int = 65_536,
    ): List<Ancestor> {
        val result = mutableListOf<Ancestor>()
        val scanFloor = (contentStart - maxScanBack).coerceAtLeast(0)
        var i = contentStart - 1
        while (i >= scanFloor && result.size < maxLevels) {
            if (html[i] == '<' && i + 1 < html.length && html[i + 1].isLetter()) {
                val gt = html.indexOf('>', i)
                if (gt in (i + 1) until contentStart && html[gt - 1] != '/') {
                    val tag = html.substring(i, gt + 1)
                    val name = tagName(tag)
                    if (name in CANDIDATE_TAG_NAMES) {
                        val closeEnd = matchingCloseEnd(html, i, gt + 1, name)
                        if (closeEnd != null && closeEnd >= contentEnd) {
                            val classAttrValue = classAttr(tag)
                            result += Ancestor(i, gt + 1, closeEnd, name, classAttrValue)
                            if (classAttrValue.contains("listingblock", ignoreCase = true)) {
                                return result
                            }
                        }
                    }
                }
            }
            i--
        }
        return result
    }

    /**
     * Returns the offset just past the matching close tag for the element opened at
     * `[openIdx, openTagEnd)` with tag [tagName], accounting for nesting of the same tag
     * name. Returns null when no balanced close tag can be found.
     */
    private fun matchingCloseEnd(
        html: String,
        openIdx: Int,
        openTagEnd: Int,
        tagName: String,
    ): Int? {
        var depth = 1
        var pos = openTagEnd
        val openPattern = Regex("""<\s*$tagName\b""", RegexOption.IGNORE_CASE)
        val closePattern = Regex("""</\s*$tagName\s*>""", RegexOption.IGNORE_CASE)
        while (pos < html.length && depth > 0) {
            val nextOpen = openPattern.find(html, pos)
            val nextClose = closePattern.find(html, pos)
            if (nextClose == null) return null
            if (nextOpen != null && nextOpen.range.first < nextClose.range.first) {
                if (!html.startsWith("</", nextOpen.range.first)) {
                    depth++
                    pos = nextOpen.range.last + 1
                    continue
                }
            }
            depth--
            pos = nextClose.range.last + 1
            if (depth == 0) {
                return pos
            }
        }
        return null
    }

    /**
     * Marker prefix shared by every container [KumlPreviewHtml] emits
     * (`kuml-diagram-container`, `kuml-diagram-error`, `kuml-diagram-empty`). Blocks are
     * rewritten sequentially into the same `html` string, so once earlier blocks have been
     * replaced, their rendered output is live text in the haystack a later block's Tier B/C/D
     * search runs over. A match whose ancestor chain passes through one of these containers is
     * therefore never this block's own original listing — it is a coincidental collision with
     * previously-rendered content (SVG text, or an escaped error message) — and must be
     * rejected outright rather than accepted via the `pre`/`code` fallback below.
     */
    private const val GENERATED_CONTAINER_CLASS_MARKER = "kuml-diagram-"

    private fun isInsideGeneratedContainer(chain: List<Ancestor>): Boolean =
        chain.any { it.classAttr.contains(GENERATED_CONTAINER_CLASS_MARKER, ignoreCase = true) }

    /**
     * Listing-fragment ancestor selection: the outermost `listingblock`/`literalblock`
     * ancestor, else the outermost element in the contiguous `pre`/`code` chain starting
     * at the innermost ancestor, else null (no recognizable code-block container — caller
     * must no-op rather than replace an unrelated span).
     */
    private fun findEnclosingListingElement(
        html: String,
        contentStart: Int,
        contentEnd: Int,
    ): Pair<Int, Int>? {
        if (contentStart >= contentEnd) return null
        val chain = ancestorChain(html, contentStart, contentEnd)
        if (isInsideGeneratedContainer(chain)) return null

        chain
            .lastOrNull { a ->
                a.classAttr.contains("listingblock", ignoreCase = true) || a.classAttr.contains("literalblock", ignoreCase = true)
            }?.let { return it.openStart to it.closeEnd }

        var lastPreCode: Ancestor? = null
        for (a in chain) {
            if (a.tagName == "pre" || a.tagName == "code") {
                lastPreCode = a
            } else {
                break
            }
        }
        lastPreCode?.let { return it.openStart to it.closeEnd }

        return null
    }

    /**
     * Macro-fragment ancestor selection: the outermost `paragraph`/`literalblock`/`listingblock`
     * ancestor, else the nearest `<p>`, else the matched span itself (unchanged fallback so
     * previously-passing macro tests keep their exact replacement range).
     */
    private fun findEnclosingMacroElement(
        html: String,
        contentStart: Int,
        contentEnd: Int,
    ): Pair<Int, Int> {
        val chain = ancestorChain(html, contentStart, contentEnd)

        chain
            .lastOrNull { a ->
                listOf("paragraph", "literalblock", "listingblock").any { hint -> a.classAttr.contains(hint, ignoreCase = true) }
            }?.let { return it.openStart to it.closeEnd }

        chain.firstOrNull { it.tagName == "p" }?.let { return it.openStart to it.closeEnd }

        return contentStart to contentEnd
    }

    private fun tagName(openTag: String): String {
        val m = Regex("""<\s*([A-Za-z][A-Za-z0-9]*)""").find(openTag)
        return m?.groupValues?.get(1)?.lowercase() ?: "div"
    }

    private fun classAttr(openTag: String): String {
        val m =
            Regex("""\bclass\s*=\s*"([^"]*)\"""", RegexOption.IGNORE_CASE).find(openTag)
                ?: Regex("""\bclass\s*=\s*'([^']*)'""", RegexOption.IGNORE_CASE).find(openTag)
        return m?.groupValues?.get(1) ?: ""
    }
}
