package dev.kuml.workspace

import dev.kuml.core.model.note.NoteKind
import dev.kuml.core.model.note.NotePrefixKinds

/**
 * Parsed `.kuml-workspace.toml` marker file at an OKF workspace root (ADR-0011).
 *
 * @property mode The declared workspace mode from `[workspace] mode`. [WorkspaceMode.UNKNOWN]
 *  when absent or unrecognised — [WorkspaceScanner] then falls back to its inference heuristic.
 * @property name The declared workspace name from `[workspace] name`.
 * @property kumlVersion The declared kUML CLI version constraint from `[workspace] kuml-version`.
 * @property okfVersion The declared OKF spec version from `[okf] version`.
 * @property vocabulary The declared `type:` vocabulary identifier from `[okf] vocabulary`
 *  (a field `workspace init`, wave 2, is expected to write; not yet emitted anywhere in wave 1).
 * @property strict The declared `[okf] strict` flag, or `null` if absent.
 * @property notePrefixKinds The opt-in `[note-prefixes]` table (note text prefix to expected note kind),
 *  [NotePrefixKinds.EMPTY] when absent. Drives `KUML-NOTE-W006`.
 */
public data class WorkspaceMarker(
    public val mode: WorkspaceMode,
    public val name: String?,
    public val kumlVersion: String?,
    public val okfVersion: String?,
    public val vocabulary: String?,
    public val strict: Boolean?,
    public val notePrefixKinds: NotePrefixKinds = NotePrefixKinds.EMPTY,
)

/**
 * Hand-rolled, dependency-free parser for the small TOML subset used by
 * `.kuml-workspace.toml` marker files (ADR-0011).
 *
 * Recognised grammar — nothing more:
 * - Blank lines are ignored.
 * - Lines whose first non-whitespace character is `#` are comments and are ignored.
 * - `[section]` headers switch the current section. Only `workspace`, `okf` and `note-prefixes` are
 *   understood; any other section (including a nested/unknown one) is parsed but its
 *   keys are ignored — forward-compatible with future sections this parser doesn't
 *   know about yet.
 * - `key = value` lines within a recognised section. `value` may be a double- or
 *   single-quoted string (quotes stripped) or a bare scalar (trimmed as-is).
 * - `strict = true` / `strict = false` (bare, unquoted) parse as [Boolean]; any other
 *   spelling is ignored (leaves [WorkspaceMarker.strict] `null`).
 * - Section-scoped keys, so `[workspace] name` and any future `[okf] name` never collide.
 *
 * Lenient by design (never throws) — matches [WorkspaceScanner]'s existing fail-soft
 * contract: a malformed marker file degrades to "field absent", not a scan failure.
 */
public object WorkspaceMarkerParser {
    private val SECTION_HEADER = Regex("""^\[([A-Za-z0-9_-]+)]$""")
    private val KEY_VALUE = Regex("""^([\p{L}\p{N}_.-]+)\s*=\s*(.*)$""")

    public fun parse(text: String): WorkspaceMarker {
        var section: String? = null
        var mode: WorkspaceMode = WorkspaceMode.UNKNOWN
        var name: String? = null
        var kumlVersion: String? = null
        var okfVersion: String? = null
        var vocabulary: String? = null
        var strict: Boolean? = null
        val prefixKinds = LinkedHashMap<String, NoteKind>()

        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val sectionMatch = SECTION_HEADER.find(line)
            if (sectionMatch != null) {
                section = sectionMatch.groupValues[1].lowercase()
                continue
            }

            val kv = KEY_VALUE.find(line) ?: continue
            val key = kv.groupValues[1].trim().lowercase()
            val rawValue = kv.groupValues[2].trim()
            val value = unquote(rawValue)

            when (section) {
                "workspace" ->
                    when (key) {
                        "mode" -> mode = modeFromString(value)
                        "name" -> name = value
                        "kuml-version" -> kumlVersion = value
                    }
                "okf" ->
                    when (key) {
                        "version" -> okfVersion = value
                        "vocabulary" -> vocabulary = value
                        "strict" -> strict = value.toBooleanStrictOrNull()
                    }
                "note-prefixes" -> {
                    // Unknown kind names are ignored (lenient); a later duplicate key wins.
                    val kind = NoteKind.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                    if (kind != null && prefixKinds.size < NotePrefixKinds.MAX_ENTRIES * 4) prefixKinds[key] = kind
                }
                else -> Unit // unknown/forward-compatible section — ignored
            }
        }

        return WorkspaceMarker(
            mode = mode,
            name = name,
            kumlVersion = kumlVersion,
            okfVersion = okfVersion,
            vocabulary = vocabulary,
            strict = strict,
            notePrefixKinds = NotePrefixKinds(prefixKinds),
        )
    }

    private fun modeFromString(raw: String): WorkspaceMode =
        when (raw.lowercase()) {
            "knowledge" -> WorkspaceMode.KNOWLEDGE
            "engineering" -> WorkspaceMode.ENGINEERING
            else -> WorkspaceMode.UNKNOWN
        }

    private fun unquote(value: String): String =
        if (value.length >= 2 &&
            ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))
        ) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
}
