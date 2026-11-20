package dev.kuml.desktop.workspace

import dev.kuml.workspace.OkfAliasFix

/**
 * Buffer text -> text with the alias `type:` replaced by its canonical id, or `null` when there is
 * nothing to do (canonical or unknown type, no frontmatter). Operates on the in-memory buffer only
 * (Nacharbeit 122b); every other byte is preserved by [OkfAliasFix].
 */
internal fun normaliseAliasTypeInBuffer(buffer: String): String? = OkfAliasFix.canonicalizeType(buffer)?.takeIf { it != buffer }
