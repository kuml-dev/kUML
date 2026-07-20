package dev.kuml.workspace

/**
 * Pure text mechanics for FT-7 (`kuml workspace convert`): wrapping a `.kuml.kts`
 * script's source into an OKF Markdown note (knowledge <- engineering) and
 * extracting a document's ` ```kuml ` block(s) back into script source
 * (engineering <- knowledge).
 *
 * Both directions are pure string manipulation — no script evaluation, no
 * metamodel dependency. Deciding *which* OKF `type:` a script's diagram maps to
 * requires evaluating the script (to know the concrete diagram kind), which
 * lives in `kuml-cli` (`OkfTypeMapper`) — this object only assembles/disassembles
 * text once the caller has already resolved a `typeId` string. This keeps
 * `kuml-docs:kuml-workspace`'s dependency direction unchanged (no
 * `kuml-core-script`, no metamodels — see the module's `build.gradle.kts`).
 */
public object OkfConverter {
    /**
     * Wraps [dslSource] into an OKF-conformant Markdown note: a YAML frontmatter
     * block with `type:`/`title:`, an H1 heading, and a single fenced ` ```kuml `
     * block containing [dslSource] verbatim.
     *
     * Round-trip note: [extractBlocks] applied to the result of this function
     * recovers [dslSource] exactly, up to a possible trailing-newline
     * normalisation (the fenced block content is always written with exactly
     * one trailing newline before the closing fence, regardless of how many
     * trailing newlines [dslSource] had). Compare with `String.trimEnd('\n')`
     * when asserting exact round-trip equality.
     *
     * @param dslSource The full `.kuml.kts` script source (unwrapped, no fences).
     * @param typeId The OKF `type:` value to write (an [OkfType.id], or a custom
     *  string when the diagram kind has no vocabulary entry — see `OkfTypeMapper`
     *  in `kuml-cli`).
     * @param title Human-readable title — becomes both the frontmatter `title:`
     *  field and the H1 heading (unless [heading] overrides the latter).
     * @param heading Optional distinct H1 text; defaults to [title].
     */
    public fun wrapAsOkf(
        dslSource: String,
        typeId: String,
        title: String,
        heading: String? = null,
    ): String {
        val h1 = heading ?: title
        return buildString {
            append("---\n")
            append("type: ").append(escapeFrontmatterScalar(typeId)).append('\n')
            append("title: ").append(escapeFrontmatterScalar(title)).append('\n')
            append("---\n")
            append('\n')
            append("# ").append(h1).append('\n')
            append('\n')
            append("```kuml\n")
            append(dslSource.trimEnd('\n'))
            append('\n')
            append("```\n")
        }
    }

    /**
     * A single ` ```kuml ` block extracted from an [OkfDocument], ready to be
     * written out as a standalone `<stem>.kuml.kts` file.
     *
     * @property stem The file stem (without `.kuml.kts`) — see [extractBlocks] for
     *  the naming rule.
     * @property dslSource The block's source, verbatim (byte-for-byte as fenced,
     *  modulo the trailing-newline normalisation documented on [wrapAsOkf]).
     * @property blockIndex The block's 0-based index within [OkfDocument.kumlBlocks].
     */
    public data class ExtractedScript(
        public val stem: String,
        public val dslSource: String,
        public val blockIndex: Int,
    )

    /**
     * Extracts every ` ```kuml ` block of [doc] as an [ExtractedScript], applying
     * the same stem-naming convention as `kuml workspace render`
     * (`WorkspaceRenderer` in `kuml-cli`): a ` ```kuml {name="…"} ` attribute wins;
     * otherwise a single block uses the document's own file stem, and multiple
     * blocks are numbered `<stem>-1`, `<stem>-2`, … (1-based) — consistent with
     * [OkfValidator]'s `OKF-W-004` "one file = one diagram" convention.
     *
     * Returns an empty list for a document with no ` ```kuml ` block (the caller
     * reports this as `OKF-C-001`, a skip — not a finding).
     */
    public fun extractBlocks(doc: OkfDocument): List<ExtractedScript> {
        val docStem = doc.file.nameWithoutExtension
        return doc.kumlBlocks.mapIndexed { idx, block ->
            val rawStem = block.name ?: if (doc.kumlBlocks.size > 1) "$docStem-${idx + 1}" else docStem
            ExtractedScript(
                stem = sanitizeStem(rawStem),
                dslSource = block.source,
                blockIndex = idx,
            )
        }
    }

    /**
     * Reduces an attacker-controlled `name` attribute (or a document/script stem
     * derived from a scanned path) to a safe file-name stem: strips any path
     * components (`/`, `\`, `..`) by keeping only the last path segment, then
     * replaces every character outside `[A-Za-z0-9._-]` with `_`. Falls back to
     * `"block"` if nothing safe remains.
     *
     * Identical rule set to `WorkspaceRenderer.sanitizeStem` (`kuml-cli`) —
     * duplicated intentionally rather than shared across the module boundary: it
     * is a small pure function, and `kuml-docs:kuml-workspace` must not gain a
     * dependency on `kuml-cli` just to reuse it. See ADR-0011 spike security
     * review.
     */
    public fun sanitizeStem(raw: String): String {
        val lastSegment = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = lastSegment.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val trimmed = cleaned.trim('.', '_', '-')
        return trimmed.ifEmpty { "block" }
    }

    /**
     * Escapes [value] for safe embedding as a single-line YAML frontmatter
     * scalar understood by [FrontmatterParser]: any embedded newline is replaced
     * with a space (a raw line break would otherwise split into a bogus second
     * frontmatter line), and the value is wrapped in double quotes — with
     * embedded backslashes/double quotes escaped — whenever it is empty or
     * contains a character ([':'], `#`) that [FrontmatterParser]'s `key: value`
     * split would otherwise misinterpret, or leading/trailing whitespace that
     * would otherwise be trimmed away.
     *
     * Note this is a one-way encode: [FrontmatterParser.unquote] strips a
     * matching pair of surrounding quotes but does not un-escape `\"`/`\\`
     * inside them, so a title containing a literal double quote does not survive
     * a full wrap → re-parse round trip byte-for-byte. That is acceptable here —
     * the `title:`/`type:` fields are metadata, never the DSL payload the
     * lossless round-trip guarantee applies to (see [wrapAsOkf]'s KDoc).
     */
    private fun escapeFrontmatterScalar(value: String): String {
        val singleLine = value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        val needsQuoting =
            singleLine.isEmpty() ||
                singleLine.contains(':') ||
                singleLine.contains('#') ||
                singleLine.contains('"') ||
                singleLine.trim() != singleLine
        if (!needsQuoting) return singleLine
        val escaped = singleLine.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
