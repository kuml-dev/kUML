package dev.kuml.workspace.pdf.layout

import dev.kuml.workspace.pdf.PageFormat
import dev.kuml.workspace.pdf.PageOrientation
import dev.kuml.workspace.pdf.PageStrategy
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Page measures (points) the planner needs. Margins differ per paper format. */
internal data class PageGeometry(
    val marginA4: Float = 56.69f,
    val marginA3: Float = 70.87f,
    val titleH: Float = 20f,
    val srcH: Float = 16f,
    /** Overlap of neighbouring tiles, 10 mm. */
    val overlap: Float = 28.35f,
) {
    fun margin(f: PageFormat): Float = if (f == PageFormat.A3) marginA3 else marginA4

    fun pageW(
        f: PageFormat,
        landscape: Boolean,
    ): Float = if (landscape) f.heightPt else f.widthPt

    fun pageH(
        f: PageFormat,
        landscape: Boolean,
    ): Float = if (landscape) f.widthPt else f.heightPt

    fun contentW(
        f: PageFormat,
        landscape: Boolean,
    ): Float = pageW(f = f, landscape = landscape) - 2 * margin(f)

    fun contentH(
        f: PageFormat,
        landscape: Boolean,
    ): Float = pageH(f = f, landscape = landscape) - 2 * margin(f) - titleH - srcH
}

internal enum class PlanMode { SINGLE, TILED }

/** Result of the page planning for one diagram. */
internal data class PagePlan(
    val format: PageFormat,
    val landscape: Boolean,
    val scale: Float,
    val cols: Int,
    val rows: Int,
    val illegible: Boolean,
) {
    val tiles: Int get() = cols * rows
    val mode: PlanMode get() = if (tiles > 1) PlanMode.TILED else PlanMode.SINGLE
}

/** Pure page planning: which paper, orientation and tile grid a diagram of a given size gets. */
internal object PagePlanner {
    const val MAX_TILES = 24
    const val AUTO_LANDSCAPE_GAIN = 0.15f
    private const val EPS = 1e-4f

    fun plan(
        w: Float,
        h: Float,
        base: PageFormat,
        orientation: PageOrientation,
        strategy: PageStrategy,
        tiling: Boolean,
        minScale: Float,
        g: PageGeometry,
    ): PagePlan {
        val dw = if (w.isFinite() && w > 0f) w else 1f
        val dh = if (h.isFinite() && h > 0f) h else 1f
        return if (strategy == PageStrategy.AUTO) {
            planAuto(w = dw, h = dh, base = base, tiling = tiling, minScale = minScale, g = g)
        } else {
            planFixed(w = dw, h = dh, base = base, orientation = orientation, tiling = tiling, minScale = minScale, g = g)
        }
    }

    private fun single(
        f: PageFormat,
        landscape: Boolean,
        s: Float,
        illegible: Boolean,
    ) = PagePlan(format = f, landscape = landscape, scale = s, cols = 1, rows = 1, illegible = illegible)

    private fun planAuto(
        w: Float,
        h: Float,
        base: PageFormat,
        tiling: Boolean,
        minScale: Float,
        g: PageGeometry,
    ): PagePlan {
        // L1: base format; R1 falls back to the other orientation when only that one is readable
        val sP = singleScale(w = w, h = h, f = base, landscape = false, g = g)
        val sL = singleScale(w = w, h = h, f = base, landscape = true, g = g)
        var landscape = sL - sP >= AUTO_LANDSCAPE_GAIN
        var chosen = if (landscape) sL else sP
        if (chosen < minScale) {
            val other = if (landscape) sP else sL
            if (other >= minScale) {
                landscape = !landscape
                chosen = other
            }
        }
        if (chosen >= minScale) return single(f = base, landscape = landscape, s = chosen, illegible = false)
        // L2: A3, only when the base format is A4
        var best = single(f = base, landscape = sL >= sP, s = max(sL, sP), illegible = true)
        if (base == PageFormat.A4) {
            val a3L = singleScale(w = w, h = h, f = PageFormat.A3, landscape = true, g = g)
            val a3P = singleScale(w = w, h = h, f = PageFormat.A3, landscape = false, g = g)
            val a3Land = a3L >= a3P
            val a3 = max(a3L, a3P)
            if (a3 >= minScale) return single(f = PageFormat.A3, landscape = a3Land, s = a3, illegible = false)
            if (a3 > best.scale) best = single(f = PageFormat.A3, landscape = a3Land, s = a3, illegible = true)
        }
        // L3: tiles in the base format
        if (tiling) return bestTiles(w = w, h = h, f = base, orientations = listOf(false, true), minScale = minScale, g = g)
        // L4: unreadable
        return best
    }

    private fun planFixed(
        w: Float,
        h: Float,
        base: PageFormat,
        orientation: PageOrientation,
        tiling: Boolean,
        minScale: Float,
        g: PageGeometry,
    ): PagePlan {
        val sP = singleScale(w = w, h = h, f = base, landscape = false, g = g)
        val sL = singleScale(w = w, h = h, f = base, landscape = true, g = g)
        val landscape =
            when (orientation) {
                PageOrientation.LANDSCAPE -> true
                PageOrientation.PORTRAIT -> false
                PageOrientation.AUTO -> sL - sP >= AUTO_LANDSCAPE_GAIN
            }
        val s = if (landscape) sL else sP
        if (s >= minScale) return single(f = base, landscape = landscape, s = s, illegible = false)
        if (!tiling) return single(f = base, landscape = landscape, s = s, illegible = true)
        val candidates =
            when (orientation) {
                PageOrientation.AUTO -> listOf(false, true)
                else -> listOf(landscape)
            }
        return bestTiles(w = w, h = h, f = base, orientations = candidates, minScale = minScale, g = g)
    }

    private fun bestTiles(
        w: Float,
        h: Float,
        f: PageFormat,
        orientations: List<Boolean>,
        minScale: Float,
        g: PageGeometry,
    ): PagePlan {
        val plans = orientations.map { tileGrid(w = w, h = h, f = f, landscape = it, minScale = minScale, g = g) }
        return plans
            .sortedWith(
                compareBy<PagePlan> { it.tiles }.thenByDescending { it.scale }.thenByDescending { it.landscape },
            ).first()
    }

    /** Scale of the diagram on one page of format [f]; never above 1. */
    fun singleScale(
        w: Float,
        h: Float,
        f: PageFormat,
        landscape: Boolean,
        g: PageGeometry,
    ): Float = min(1f, min(g.contentW(f = f, landscape = landscape) / w, g.contentH(f = f, landscape = landscape) / h))

    /** Smallest grid that reaches [minScale]; capped at [MAX_TILES] tiles, then maximizing the scale. */
    fun tileGrid(
        w: Float,
        h: Float,
        f: PageFormat,
        landscape: Boolean,
        minScale: Float,
        g: PageGeometry,
    ): PagePlan {
        val cw = g.contentW(f = f, landscape = landscape)
        val ch = g.contentH(f = f, landscape = landscape)
        val ov = g.overlap

        fun need(
            d: Float,
            c: Float,
        ): Int {
            val scaled = d * minScale
            return if (scaled <= c) 1 else max(1, ceil((scaled - ov) / (c - ov)).toInt())
        }

        fun scaleOf(
            c: Int,
            r: Int,
        ): Float = min(1f, min((c * (cw - ov) + ov) / w, (r * (ch - ov) + ov) / h))
        val needC = need(w, cw)
        val needR = need(h, ch)
        var cols: Int
        var rows: Int
        if (needC.toLong() * needR <= MAX_TILES) {
            cols = needC
            rows = needR
        } else {
            cols = 1
            rows = 1
            var bestS = -1f
            for (c in 1..MAX_TILES) {
                for (r in 1..(MAX_TILES / c)) {
                    val s = scaleOf(c, r)
                    if (s > bestS + EPS || (s > bestS - EPS && c * r < cols * rows)) {
                        bestS = s
                        cols = c
                        rows = r
                    }
                }
            }
        }
        val s = scaleOf(cols, rows)
        // shrink to the minimal grid for this scale: no empty edge tiles
        cols = max(1, ceil((w * s - ov) / (cw - ov) - EPS).toInt()).coerceAtMost(cols)
        rows = max(1, ceil((h * s - ov) / (ch - ov) - EPS).toInt()).coerceAtMost(rows)
        return PagePlan(format = f, landscape = landscape, scale = s, cols = cols, rows = rows, illegible = s < minScale)
    }
}
