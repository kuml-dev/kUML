package dev.kuml.desktop.preview

import org.w3c.dom.svg.SVGDocument
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import kotlin.math.max
import kotlin.math.min

// Pure view maths for the preview canvas. Batik draws a document with the screen transform
// `rendering · viewing`: `viewing` is the viewBox-to-window ("fit") transform it recomputes on
// every resize, `rendering` is the user transform (identity = fit) that zoom and pan change.
// Every function here takes the content rectangle in viewBox coordinates and works on the
// COMBINED transform, but only ever returns a new `rendering` transform.

/** Fraction of the content that must stay visible on each axis. */
internal const val PREVIEW_MIN_VISIBLE_FRACTION = 0.25

/** Absolute visible strip (px) kept on each axis, capped at the content's own on-screen size. */
internal const val PREVIEW_MIN_VISIBLE_PX = 96.0

private const val MIN_CONTENT_PX = 1.0

private fun AffineTransform.isFinite(): Boolean =
    doubleArrayOf(scaleX, shearY, shearX, scaleY, translateX, translateY).all { it.isFinite() }

private fun Rectangle2D.isUsable(): Boolean =
    x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() && width > 0.0 && height > 0.0

private fun AffineTransform.isInvertible(): Boolean = isFinite() && determinant.let { it.isFinite() && it != 0.0 }

private fun combined(
    current: AffineTransform,
    viewing: AffineTransform,
): AffineTransform = AffineTransform(current).apply { concatenate(viewing) }

/** [current] with only its translation changed by ([dx], [dy]); the linear part stays bit-identical. */
private fun shifted(
    current: AffineTransform,
    dx: Double,
    dy: Double,
): AffineTransform =
    AffineTransform(current.scaleX, current.shearY, current.shearX, current.scaleY, current.translateX + dx, current.translateY + dy)

/**
 * The rendering transform that shows [content] at 1:1 (one viewBox unit per pixel in the combined
 * transform), centred in the viewport. Identity when the input cannot be resolved.
 */
internal fun actualSizeTransform(
    viewing: AffineTransform,
    content: Rectangle2D,
    viewportW: Double,
    viewportH: Double,
): AffineTransform {
    if (!viewing.isInvertible() || !content.isUsable() || !viewportW.isFinite() || !viewportH.isFinite()) return AffineTransform()
    val sx = viewing.scaleX
    val sy = viewing.scaleY
    if (sx == 0.0 || sy == 0.0) return AffineTransform()
    val kx = 1.0 / sx
    val ky = 1.0 / sy
    val p =
        viewing.transform(
            java.awt.geom.Point2D
                .Double(content.centerX, content.centerY),
            null,
        )
    val result = AffineTransform(kx, 0.0, 0.0, ky, viewportW / 2.0 - kx * p.x, viewportH / 2.0 - ky * p.y)
    return if (result.isFinite()) result else AffineTransform()
}

/** [current] moved so the content's centre lies on the viewport centre; the zoom is untouched. */
internal fun centeredTransform(
    current: AffineTransform,
    viewing: AffineTransform,
    content: Rectangle2D,
    viewportW: Double,
    viewportH: Double,
): AffineTransform {
    if (!current.isFinite() || !viewing.isFinite() || !content.isUsable() || !viewportW.isFinite() || !viewportH.isFinite()) return current
    val screen = combined(current = current, viewing = viewing).createTransformedShape(content).bounds2D
    if (!screen.centerX.isFinite() || !screen.centerY.isFinite()) return current
    return shifted(current = current, dx = viewportW / 2.0 - screen.centerX, dy = viewportH / 2.0 - screen.centerY)
}

/**
 * Keeps at least [minFraction] of the content, but at least [minPx] pixels (never more than the
 * content itself), inside the viewport on both axes by shifting the translation only. The zoom is
 * never changed; a transform already inside the limits is returned as the same object.
 */
internal fun clampedTransform(
    current: AffineTransform,
    viewing: AffineTransform,
    content: Rectangle2D,
    viewportW: Double,
    viewportH: Double,
    minFraction: Double = PREVIEW_MIN_VISIBLE_FRACTION,
    minPx: Double = PREVIEW_MIN_VISIBLE_PX,
): AffineTransform {
    if (!current.isFinite() || !viewing.isFinite() || !content.isUsable()) return current
    if (!viewportW.isFinite() || !viewportH.isFinite() || viewportW <= 0.0 || viewportH <= 0.0) return current
    val s = combined(current = current, viewing = viewing).createTransformedShape(content).bounds2D
    if (!s.isUsable() || s.width < MIN_CONTENT_PX || s.height < MIN_CONTENT_PX) return current
    val needX = min(viewportW, min(s.width, max(minFraction * s.width, minPx)))
    val needY = min(viewportH, min(s.height, max(minFraction * s.height, minPx)))
    val dx = axisShift(lo = s.minX, hi = s.maxX, need = needX, viewport = viewportW)
    val dy = axisShift(lo = s.minY, hi = s.maxY, need = needY, viewport = viewportH)
    return if (dx == 0.0 && dy == 0.0) current else shifted(current = current, dx = dx, dy = dy)
}

private fun axisShift(
    lo: Double,
    hi: Double,
    need: Double,
    viewport: Double,
): Double =
    when {
        hi < need -> need - hi
        lo > viewport - need -> (viewport - need) - lo
        else -> 0.0
    }

/**
 * The content rectangle in viewBox coordinates: the root's `viewBox`, else `width`/`height`
 * (pixels or unitless only), else [fallback]. Hostile or malformed attributes never throw.
 */
internal fun contentBoundsOf(
    doc: SVGDocument?,
    fallback: () -> Rectangle2D?,
): Rectangle2D? {
    val root = doc?.documentElement
    if (root != null) {
        parseViewBox(root.getAttribute("viewBox"))?.let { return it }
        val w = parseLength(root.getAttribute("width"))
        val h = parseLength(root.getAttribute("height"))
        if (w != null && h != null) return Rectangle2D.Double(0.0, 0.0, w, h)
    }
    return runCatching(fallback).getOrNull()?.takeIf { it.isUsable() }
}

private fun parseViewBox(text: String?): Rectangle2D? {
    val parts = text?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() } ?: return null
    if (parts.size != 4) return null
    return Rectangle2D.Double(parts[0], parts[1], parts[2], parts[3]).takeIf { it.isUsable() }
}

private fun parseLength(text: String?): Double? {
    val t = text?.trim().orEmpty()
    val number = if (t.endsWith("px")) t.removeSuffix("px").trim() else t
    return number.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
}

/** Which view the user last asked for; decides what a window resize re-applies. */
internal enum class PreviewLastView { Fit, ActualSize, Free }

/** After a resize: 1:1 is re-applied, anything else is only clamped. */
internal fun reapplyAfterResize(
    lastView: PreviewLastView,
    actualSize: () -> Unit,
    clamp: () -> Unit,
) {
    if (lastView == PreviewLastView.ActualSize) actualSize() else clamp()
}
