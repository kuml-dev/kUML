package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt3
import dev.kuml.layout.GroupId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.Sysml2ReqCard
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.UcDiagram

/** Height of the name tab of a requirement-group frame (px). */
internal const val REQ_GROUP_TAB_HEIGHT: Float = 20f

/**
 * Draws the requirement-group frames (wave C1): a rectangle with a name tab in the top-left
 * corner (1 px solid, bold 11 px name). Frames are painted outermost first so nested groups and
 * their requirements layer on top. Every user-controlled string goes through the builder's
 * escaping; attribute values are escaped once by the builder.
 */
internal fun renderRequirementGroupFrames(
    diagram: ReqDiagram,
    layoutResult: LayoutResult,
    paddingPx: Float,
    builder: SvgBuilder,
) {
    val frames =
        diagram.groups
            .mapNotNull { g -> layoutResult.groups[GroupId(g.id)]?.let { g to shift(raw = it.bounds, paddingPx = paddingPx) } }
            .sortedByDescending { (_, b) -> b.size.width * b.size.height }
    for ((group, b) in frames) {
        val x = b.origin.x
        val y = b.origin.y
        val w = b.size.width
        val h = b.size.height
        builder.tag(name = "g", attrs = mapOf("id" to group.id, "class" to "kuml-req-group")) {
            tag(
                name = "rect",
                attrs =
                    mapOf(
                        "x" to fmt3(x),
                        "y" to fmt3(y),
                        "width" to fmt3(w),
                        "height" to fmt3(h),
                        "class" to "kuml-class",
                        "stroke-width" to "1",
                    ),
            )
        }
    }
}

/**
 * Draws the name tabs of the requirement-group frames (wave C2 fix of C1): they are painted
 * after all edges, with the opaque theme node fill, so no edge line ever runs across a tab.
 * Outermost frames first, like [renderRequirementGroupFrames].
 */
internal fun renderRequirementGroupTabs(
    diagram: ReqDiagram,
    layoutResult: LayoutResult,
    paddingPx: Float,
    builder: SvgBuilder,
) {
    val frames =
        diagram.groups
            .mapNotNull { g -> layoutResult.groups[GroupId(g.id)]?.let { g to shift(raw = it.bounds, paddingPx = paddingPx) } }
            .sortedByDescending { (_, b) -> b.size.width * b.size.height }
    for ((group, b) in frames) {
        val x = b.origin.x
        val y = b.origin.y
        val w = b.size.width
        val maxNameChars = ((w - 16f) / 7.8f).toInt().coerceAtLeast(3)
        val name = Sysml2ReqCard.clip(s = group.name, max = maxNameChars)
        val tabW = minOf(w, name.length * 7.8f + 16f)
        builder.tag(name = "g", attrs = mapOf("id" to "tab:${group.id}", "class" to "kuml-req-group-tab")) {
            tag(
                name = "rect",
                attrs =
                    mapOf(
                        "x" to fmt3(x),
                        "y" to fmt3(y),
                        "width" to fmt3(tabW),
                        "height" to fmt3(REQ_GROUP_TAB_HEIGHT),
                        "class" to "kuml-class",
                        "stroke-width" to "1",
                        "fill-opacity" to "1",
                    ),
            )
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-title",
                        "x" to fmt3(x + 8f),
                        "y" to fmt3(y + 14f),
                        "text-anchor" to "start",
                        "font-size" to "11",
                        "font-weight" to "bold",
                    ),
            ) { text(name) }
        }
    }
}

/**
 * Draws the system-boundary frames of a use-case diagram (wave C1): a rectangle with the
 * subject name in bold 12 px, left-aligned at the top. Use cases lie inside by construction
 * (they are compound children), actors outside.
 */
internal fun renderUcSubjectFrames(
    diagram: UcDiagram,
    layoutResult: LayoutResult,
    paddingPx: Float,
    builder: SvgBuilder,
) {
    for (subject in diagram.subjects) {
        val b = layoutResult.groups[GroupId(subject.id)]?.let { shift(raw = it.bounds, paddingPx = paddingPx) } ?: continue
        builder.tag(name = "g", attrs = mapOf("id" to subject.id, "class" to "kuml-uc-subject")) {
            tag(
                name = "rect",
                attrs =
                    mapOf(
                        "x" to fmt3(b.origin.x),
                        "y" to fmt3(b.origin.y),
                        "width" to fmt3(b.size.width),
                        "height" to fmt3(b.size.height),
                        "class" to "kuml-class",
                        "stroke-width" to "1.5",
                    ),
            )
        }
    }
}

/**
 * Draws the subject names of the use-case system boundaries (wave C2 fix of C1) after all edges,
 * on an opaque backing rectangle, so an association never runs through the title.
 */
internal fun renderUcSubjectTitles(
    diagram: UcDiagram,
    layoutResult: LayoutResult,
    paddingPx: Float,
    builder: SvgBuilder,
) {
    for (subject in diagram.subjects) {
        val b = layoutResult.groups[GroupId(subject.id)]?.let { shift(raw = it.bounds, paddingPx = paddingPx) } ?: continue
        val maxNameChars = ((b.size.width - 24f) / 7f).toInt().coerceAtLeast(3)
        val name = Sysml2ReqCard.clip(s = subject.name, max = maxNameChars)
        val backW = minOf(b.size.width - 4f, name.length * 7.4f + 16f)
        builder.tag(name = "g", attrs = mapOf("id" to "title:${subject.id}", "class" to "kuml-uc-subject-title")) {
            tag(
                name = "rect",
                attrs =
                    mapOf(
                        "x" to fmt3(b.origin.x + 6f),
                        "y" to fmt3(b.origin.y + 6f),
                        "width" to fmt3(backW),
                        "height" to fmt3(22f),
                        "class" to "kuml-class",
                        "style" to "stroke:none",
                        "fill-opacity" to "1",
                    ),
            )
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-title",
                        "x" to fmt3(b.origin.x + 12f),
                        "y" to fmt3(b.origin.y + 22f),
                        "text-anchor" to "start",
                        "font-size" to "12",
                        "font-weight" to "bold",
                    ),
            ) { text(name) }
        }
    }
}

private fun shift(
    raw: Rect,
    paddingPx: Float,
): Rect = Rect(origin = raw.origin.copy(x = raw.origin.x + paddingPx, y = raw.origin.y + paddingPx), size = raw.size)
