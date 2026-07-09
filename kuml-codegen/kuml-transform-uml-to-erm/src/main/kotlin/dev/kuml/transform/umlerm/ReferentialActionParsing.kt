package dev.kuml.transform.umlerm

import dev.kuml.erm.model.ReferentialAction

/**
 * Parses a `«FK».onDelete`/`«FK».onUpdate` tag string (see
 * [dev.kuml.profile.erm.ErmMappingProfile]) into a [ReferentialAction].
 *
 * Falls back to [ReferentialAction.NO_ACTION] for `null` or unrecognised values —
 * an unrecognised referential-action string is not fatal (unlike an unsafe SQL
 * identifier), it just means "no explicit override".
 */
internal fun parseReferentialAction(raw: String?): ReferentialAction =
    raw?.let { runCatching { ReferentialAction.valueOf(it.trim().uppercase()) }.getOrNull() } ?: ReferentialAction.NO_ACTION
