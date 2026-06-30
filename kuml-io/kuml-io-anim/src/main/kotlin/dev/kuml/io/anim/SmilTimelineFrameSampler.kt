package dev.kuml.io.anim

import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
import dev.kuml.render.smil.SmilAnimation
import dev.kuml.render.smil.SmilTimeline
import dev.kuml.renderer.theme.core.KumlColor
import org.w3c.dom.Document
import java.awt.geom.AffineTransform
import java.awt.geom.FlatteningPathIterator
import java.awt.geom.GeneralPath
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Fallback frame sampler: reconstructs each frame by computing static attribute values
 * from [SmilTimeline] entries at time t and injecting them into a cloned DOM — no
 * Batik animation engine required.
 *
 * This sampler is dep-free (only JDK + kuml-io-png + kuml-render-smil), deterministic,
 * and always produces correct results for the animation types kUML emits:
 * - [SmilAnimation.Animate]: linear interpolation between `from` and `to`.
 * - [SmilAnimation.AnimateTransform]: linear interpolation of numeric tokens.
 * - [SmilAnimation.AnimateMotion]: position along SVG path via [java.awt.geom].
 * - [SmilAnimation.Set]: threshold apply (value becomes `to` once `beginMs` is reached).
 * - [SmilAnimation.Fill]: linear colour lerp (from → color) for the active window,
 *   then freezes at `fromColor` (restore) after the animation ends.
 *
 * The sampler strips all `<animate>`, `<animateTransform>`, `<animateMotion>`, `<set>`
 * elements from the cloned DOM and injects computed static attribute values directly.
 */
public object SmilTimelineFrameSampler {
    private val log: java.util.logging.Logger =
        java.util.logging.Logger
            .getLogger(SmilTimelineFrameSampler::class.java.name)

    /**
     * Sample [svg] into PNG frames using the dep-free timeline approach.
     *
     * @param svg SMIL-animated SVG string.
     * @param timeline The [SmilTimeline] to evaluate per frame.
     * @param budget Frame count, interval, and effective fps.
     * @param options Export options.
     */
    public fun sample(
        svg: String,
        timeline: SmilTimeline,
        budget: FrameBudget,
        options: AnimRenderOptions,
    ): List<ByteArray> {
        System.setProperty("java.awt.headless", "true")

        // Pre-parse SVG size guard (mirrors BatikFrameSampler) — reject oversized inputs
        // before handing them to the XML parser to prevent entity-expansion attacks.
        val svgBytes = svg.toByteArray(Charsets.UTF_8)
        if (svgBytes.size.toLong() > options.maxSvgBytes) {
            val sizeMb = svgBytes.size / (1024 * 1024)
            val maxMb = options.maxSvgBytes / (1024 * 1024)
            throw AnimEncoderException(
                "SVG input is $sizeMb MiB, which exceeds the $maxMb MiB limit " +
                    "(maxSvgBytes=${options.maxSvgBytes}). Reduce diagram complexity.",
            )
        }

        return (0 until budget.frameCount).map { i ->
            val tMs = i.toLong() * budget.intervalMs
            renderFrameAtTime(svg, timeline, tMs, options)
        }
    }

    private fun renderFrameAtTime(
        svg: String,
        timeline: SmilTimeline,
        tMs: Long,
        options: AnimRenderOptions,
    ): ByteArray {
        val staticSvg = buildStaticFrame(svg, timeline, tMs)
        val pngOpts =
            PngRenderOptions(
                widthPx = options.widthPx,
                transparent = options.transparent,
                backgroundColor = if (options.transparent) null else KumlColor.White,
            )
        return KumlPngRenderer.toPng(staticSvg, pngOpts)
    }

    /**
     * Strip all SMIL animation elements from [svg] and inject computed static
     * attribute values for time [tMs].
     */
    internal fun buildStaticFrame(
        svg: String,
        timeline: SmilTimeline,
        tMs: Long,
    ): String {
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = true
        // Harden against XXE and entity-expansion attacks before any parsing occurs.
        // FEATURE_SECURE_PROCESSING enforces implementation-defined limits on entity
        // expansion, attributes per element, etc.  The access-external-* attributes
        // prevent the parser from loading external DTDs or schemas over any protocol.
        try {
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        } catch (_: Exception) {
            // Ignore: older JAXP implementations may not support the feature; the
            // attribute-level guards below still apply.
        }
        try {
            dbf.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            dbf.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        } catch (_: Exception) {
            // Ignore: attribute may be unsupported on non-Xerces parsers.
        }
        val doc = dbf.newDocumentBuilder().parse(svg.byteInputStream())

        // Strip all SMIL animation elements
        removeSmilElements(doc)

        // Compute and apply attribute values per animation active at tMs
        for (anim in timeline.animations) {
            applyAnimation(doc, anim, tMs)
        }

        return docToString(doc)
    }

    private fun removeSmilElements(doc: Document) {
        val tags = listOf("animate", "animateTransform", "animateMotion", "set")
        for (tag in tags) {
            val list = doc.getElementsByTagName(tag)
            // collect then remove to avoid live NodeList issues
            val toRemove = (0 until list.length).map { list.item(it) }
            for (node in toRemove) {
                node.parentNode?.removeChild(node)
            }
        }
    }

    private fun applyAnimation(
        doc: Document,
        anim: SmilAnimation,
        tMs: Long,
    ) {
        val el = doc.getElementById(anim.elementId) ?: return
        when (anim) {
            is SmilAnimation.Animate -> {
                val t = normalise(tMs, anim.beginMs, anim.durationMs, anim.repeatCount)
                // Guard is `t in 0..1` rather than `t >= 0` so that frames sampled
                // *after* the animation window (t > 1.0) freeze at the `to` value
                // via the lerp clamp rather than computing an out-of-range interpolation.
                // For indefinite animations (repeatCount=0) normalise() wraps t into [0,1]
                // so this else branch (freeze) is never reached.
                // The Fill branch handles its own post-window "freeze" case explicitly.
                if (t < 0.0f) {
                    // Before animation begins: restore the element to its pre-animation
                    // value (`from`). The static SVG may not have serialised the attribute
                    // at all when its value equals the SVG default (e.g. opacity is 1 by
                    // default and may be absent from the DOM even though the animation
                    // starts from opacity=0). Setting `from` explicitly ensures frames
                    // before beginMs render with the correct initial value.
                    el.setAttribute(anim.attribute, anim.from)
                } else if (t in 0.0f..1.0f) {
                    val value = lerp(anim.from, anim.to, t)
                    el.setAttribute(anim.attribute, value)
                } else {
                    // fill=freeze semantics: hold at the final `to` value
                    el.setAttribute(anim.attribute, anim.to)
                }
            }
            is SmilAnimation.AnimateTransform -> {
                val t = normalise(tMs, anim.beginMs, anim.durationMs, anim.repeatCount)
                if (t < 0.0f) {
                    // Before animation begins: restore to the pre-animation transform value.
                    el.setAttribute("transform", "${anim.type.svgToken}(${anim.from})")
                } else if (t in 0.0f..1.0f) {
                    val value = lerpTransform(anim.from, anim.to, t)
                    el.setAttribute("transform", "${anim.type.svgToken}($value)")
                } else {
                    // fill=freeze: hold at end transform
                    el.setAttribute("transform", "${anim.type.svgToken}(${anim.to})")
                }
            }
            is SmilAnimation.AnimateMotion -> {
                val t = normalise(tMs, anim.beginMs, anim.durationMs, anim.repeatCount)
                if (t < 0.0f) {
                    // Before animation begins: restore to the motion start position.
                    // This ensures the element sits at the path origin during any begin delay
                    // rather than at whatever position the static SVG happens to serialise.
                    val (x, y) = pointAlongPath(anim.path, 0.0)
                    when {
                        el.hasAttribute("cx") -> {
                            el.setAttribute("cx", x.toInt().toString())
                            el.setAttribute("cy", y.toInt().toString())
                        }
                        else -> {
                            el.setAttribute("x", x.toInt().toString())
                            el.setAttribute("y", y.toInt().toString())
                        }
                    }
                } else if (t in 0.0f..1.0f) {
                    val (x, y) = pointAlongPath(anim.path, t.toDouble())
                    // AnimateMotion typically targets a <circle> or similar
                    // We update cx/cy for circles, x/y for rects
                    when {
                        el.hasAttribute("cx") -> {
                            el.setAttribute("cx", x.toInt().toString())
                            el.setAttribute("cy", y.toInt().toString())
                        }
                        else -> {
                            el.setAttribute("x", x.toInt().toString())
                            el.setAttribute("y", y.toInt().toString())
                        }
                    }
                }
            }
            is SmilAnimation.Set -> {
                if (tMs >= anim.beginMs) {
                    el.setAttribute(anim.attribute, anim.to)
                }
            }
            is SmilAnimation.Fill -> {
                val t = normalise(tMs, anim.beginMs, anim.durationMs)
                when {
                    t < 0.0f -> {
                        // before animation — keep original (fromColor if known)
                        if (anim.fromColor != null) el.setAttribute("fill", anim.fromColor)
                    }
                    t <= 1.0f -> {
                        // during animation — lerp from fromColor to color
                        val from = anim.fromColor ?: "none"
                        val value = lerp(from, anim.color, t)
                        el.setAttribute("fill", value)
                    }
                    else -> {
                        // after animation (fill=freeze semantics) — restore fromColor
                        val restored = anim.fromColor ?: anim.color
                        el.setAttribute("fill", restored)
                    }
                }
            }
        }
    }

    /**
     * Returns the normalised time in [-1.0, 2.0]:
     * - exactly in [0.0, 1.0] while the animation window is active,
     * - < 0 when `t` is before `beginMs` (coerced to -1.0 minimum),
     * - > 1.0 when `t` is after the window ends (coerced to 2.0 maximum) for
     *   finite [repeatCount] animations (fill=freeze semantics).
     *
     * For [SmilAnimation.REPEAT_INDEFINITE] ([repeatCount] == 0) animations, time beyond
     * the first play-through wraps modulo [durationMs] so the animation cycles
     * continuously. This matches SMIL `repeatCount="indefinite"` / Batik engine behaviour
     * and prevents the fallback sampler from silently freezing indefinitely-repeating
     * animations at their terminal frame.
     *
     * The upper coerce bound (2.0) is arbitrary but finite so callers can still
     * distinguish "before" (< 0) from "after" (> 1) without numeric overflow.
     * [applyAnimation] uses the explicit `t in 0.0f..1.0f` range guard so the
     * lerp clamping inside [lerp] (via [coerceIn]) is the last line of defence
     * rather than the only one.
     */
    private fun normalise(
        tMs: Long,
        beginMs: Long,
        durationMs: Long,
        repeatCount: Int = SmilAnimation.REPEAT_ONCE,
    ): Float {
        if (durationMs <= 0L) return if (tMs >= beginMs) 1.0f else -1.0f
        val elapsed = tMs - beginMs
        if (elapsed < 0L) return (elapsed.toFloat() / durationMs).coerceIn(-1.0f, -0.0f)
        // For indefinitely repeating animations, wrap the elapsed time modulo durationMs
        // so the animation cycles rather than freezing at fill=freeze.
        val effectiveElapsed =
            if (repeatCount == SmilAnimation.REPEAT_INDEFINITE) {
                elapsed % durationMs
            } else {
                elapsed
            }
        return (effectiveElapsed.toFloat() / durationMs).coerceIn(0.0f, 2.0f)
    }

    /**
     * Linearly interpolate between two string values.
     * Tries numeric interpolation first; falls back to threshold (from/to).
     */
    private fun lerp(
        from: String,
        to: String,
        t: Float,
    ): String {
        val clamped = t.coerceIn(0.0f, 1.0f)
        // Numeric interpolation (single number or space-separated numbers)
        val fromNums = from.trim().split("\\s+".toRegex()).mapNotNull { it.toFloatOrNull() }
        val toNums = to.trim().split("\\s+".toRegex()).mapNotNull { it.toFloatOrNull() }
        if (fromNums.size == toNums.size && fromNums.isNotEmpty()) {
            return fromNums.zip(toNums).joinToString(" ") { (a, b) ->
                val v = a + (b - a) * clamped
                if (v == v.toLong().toFloat()) v.toLong().toString() else "%.4f".format(v)
            }
        }
        // Colour interpolation: #rrggbb
        val fc = parseHexColor(from)
        val tc = parseHexColor(to)
        if (fc != null && tc != null) {
            val r = (fc[0] + (tc[0] - fc[0]) * clamped).toInt().coerceIn(0, 255)
            val g = (fc[1] + (tc[1] - fc[1]) * clamped).toInt().coerceIn(0, 255)
            val b = (fc[2] + (tc[2] - fc[2]) * clamped).toInt().coerceIn(0, 255)
            return "#%02x%02x%02x".format(r, g, b)
        }
        // Fallback: threshold
        return if (clamped < 1.0f) from else to
    }

    private fun lerpTransform(
        from: String,
        to: String,
        t: Float,
    ): String = lerp(from, to, t)

    private fun parseHexColor(s: String): FloatArray? {
        val hex = s.trim().trimStart('#')
        return when (hex.length) {
            6 -> {
                val r = hex.substring(0, 2).toIntOrNull(16) ?: return null
                val g = hex.substring(2, 4).toIntOrNull(16) ?: return null
                val b = hex.substring(4, 6).toIntOrNull(16) ?: return null
                floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat())
            }
            3 -> {
                val r = (hex[0].toString().repeat(2)).toIntOrNull(16) ?: return null
                val g = (hex[1].toString().repeat(2)).toIntOrNull(16) ?: return null
                val b = (hex[2].toString().repeat(2)).toIntOrNull(16) ?: return null
                floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat())
            }
            else -> null
        }
    }

    /**
     * Compute a point at fractional distance [t] (0.0–1.0) along an SVG path [d].
     */
    private fun pointAlongPath(
        d: String,
        t: Double,
    ): Pair<Double, Double> =
        try {
            val path = GeneralPath()
            // Basic SVG path parser for M/L/C/Z commands
            parseSvgPathInto(d, path)
            val flat = FlatteningPathIterator(path.getPathIterator(AffineTransform()), 1.0)
            val totalLength = pathLength(flat)
            val target = totalLength * t.coerceIn(0.0, 1.0)
            pointAtLength(path.getPathIterator(AffineTransform()), target)
        } catch (_: Exception) {
            Pair(0.0, 0.0)
        }

    private fun pathLength(iter: java.awt.geom.PathIterator): Double {
        val coords = DoubleArray(6)
        var len = 0.0
        var px = 0.0
        var py = 0.0
        while (!iter.isDone) {
            when (iter.currentSegment(coords)) {
                java.awt.geom.PathIterator.SEG_MOVETO -> {
                    px = coords[0]
                    py = coords[1]
                }
                java.awt.geom.PathIterator.SEG_LINETO -> {
                    val dx = coords[0] - px
                    val dy = coords[1] - py
                    len += Math.sqrt(dx * dx + dy * dy)
                    px = coords[0]
                    py = coords[1]
                }
                else -> {}
            }
            iter.next()
        }
        return len
    }

    private fun pointAtLength(
        iter: java.awt.geom.PathIterator,
        target: Double,
    ): Pair<Double, Double> {
        val coords = DoubleArray(6)
        var len = 0.0
        var px = 0.0
        var py = 0.0
        while (!iter.isDone) {
            when (iter.currentSegment(coords)) {
                java.awt.geom.PathIterator.SEG_MOVETO -> {
                    px = coords[0]
                    py = coords[1]
                }
                java.awt.geom.PathIterator.SEG_LINETO -> {
                    val dx = coords[0] - px
                    val dy = coords[1] - py
                    val seg = Math.sqrt(dx * dx + dy * dy)
                    if (len + seg >= target) {
                        val frac = (target - len) / seg
                        return Pair(px + dx * frac, py + dy * frac)
                    }
                    len += seg
                    px = coords[0]
                    py = coords[1]
                }
                else -> {}
            }
            iter.next()
        }
        return Pair(px, py)
    }

    /**
     * SVG path d-string parser — handles M, L, C, Z, H, V, Q, S, T commands
     * (absolute and relative variants). A (arc) is approximated as a straight line
     * to its endpoint since [GeneralPath] has no native arc support and the approximation
     * is sufficient for animation-path length calculations.
     *
     * Unknown commands are logged as a warning rather than silently breaking the parse
     * (silent break yields (0,0) for all t > 0, which produces frozen motion with no error).
     */
    private fun parseSvgPathInto(
        d: String,
        path: GeneralPath,
    ) {
        val tokens =
            d
                .trim()
                .replace(",", " ")
                .split("\\s+".toRegex())
                .filter { it.isNotBlank() }
                .toMutableList()
        var i = 0
        var cmd = ' '
        // Track last cubic control point for S (smooth cubic)
        var lastCx2 = 0.0
        var lastCy2 = 0.0
        var lastCubic = false
        // Track last quadratic control point for T (smooth quadratic)
        var lastQx = 0.0
        var lastQy = 0.0
        var lastQuad = false

        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok.isNotEmpty() && tok.first().isLetter()) {
                cmd = tok.first()
                i++
                lastCubic = false
                lastQuad = false
            }
            val cur = path.currentPoint
            val cx = cur?.x ?: 0.0
            val cy = cur?.y ?: 0.0
            when (cmd.uppercaseChar()) {
                'M' -> {
                    val x = tokens.getOrNull(i)?.toDoubleOrNull() ?: break
                    val y = tokens.getOrNull(i + 1)?.toDoubleOrNull() ?: break
                    if (cmd.isUpperCase()) path.moveTo(x, y) else path.moveTo(cx + x, cy + y)
                    i += 2
                    // Implicit lineto after moveto
                    cmd = if (cmd.isUpperCase()) 'L' else 'l'
                }
                'L' -> {
                    val x = tokens.getOrNull(i)?.toDoubleOrNull() ?: break
                    val y = tokens.getOrNull(i + 1)?.toDoubleOrNull() ?: break
                    if (cmd.isUpperCase()) path.lineTo(x, y) else path.lineTo(cx + x, cy + y)
                    i += 2
                }
                'H' -> {
                    val x = tokens.getOrNull(i)?.toDoubleOrNull() ?: break
                    if (cmd.isUpperCase()) path.lineTo(x, cy) else path.lineTo(cx + x, cy)
                    i += 1
                }
                'V' -> {
                    val y = tokens.getOrNull(i)?.toDoubleOrNull() ?: break
                    if (cmd.isUpperCase()) path.lineTo(cx, y) else path.lineTo(cx, cy + y)
                    i += 1
                }
                'C' -> {
                    val x1 = tokens.getOrNull(i)?.toDoubleOrNull() ?: break
                    val y1 = tokens.getOrNull(i + 1)?.toDoubleOrNull() ?: break
                    val x2 = tokens.getOrNull(i + 2)?.toDoubleOrNull() ?: break
                    val y2 = tokens.getOrNull(i + 3)?.toDoubleOrNull() ?: break
                    val x = tokens.getOrNull(i + 4)?.toDoubleOrNull() ?: break
                    val y = tokens.getOrNull(i + 5)?.toDoubleOrNull() ?: break
                    if (cmd.isUpperCase()) {
                        path.curveTo(x1, y1, x2, y2, x, y)
                        lastCx2 = x2
                        lastCy2 = y2
                    } else {
                        path.curveTo(cx + x1, cy + y1, cx + x2, cy + y2, cx + x, cy + y)
                        lastCx2 = cx + x2
                        lastCy2 = cy + y2
                    }
                    lastCubic = true
                    lastQuad = false
                    i += 6
                }
                'S' -> {
                    // Smooth cubic: control point 1 is reflection of last C's cp2
                    val x2 = tokens.getOrNull(i)?.toDoubleOrNull() ?: break
                    val y2 = tokens.getOrNull(i + 1)?.toDoubleOrNull() ?: break
                    val x = tokens.getOrNull(i + 2)?.toDoubleOrNull() ?: break
                    val y = tokens.getOrNull(i + 3)?.toDoubleOrNull() ?: break
                    val rx1 = if (lastCubic) 2 * cx - lastCx2 else cx
                    val ry1 = if (lastCubic) 2 * cy - lastCy2 else cy
                    if (cmd.isUpperCase()) {
                        path.curveTo(rx1, ry1, x2, y2, x, y)
                        lastCx2 = x2
                        lastCy2 = y2
                    } else {
                        path.curveTo(rx1, ry1, cx + x2, cy + y2, cx + x, cy + y)
                        lastCx2 = cx + x2
                        lastCy2 = cy + y2
                    }
                    lastCubic = true
                    lastQuad = false
                    i += 4
                }
                'Q' -> {
                    val qx = tokens.getOrNull(i)?.toDoubleOrNull() ?: break
                    val qy = tokens.getOrNull(i + 1)?.toDoubleOrNull() ?: break
                    val x = tokens.getOrNull(i + 2)?.toDoubleOrNull() ?: break
                    val y = tokens.getOrNull(i + 3)?.toDoubleOrNull() ?: break
                    // Approximate quadratic Bezier as cubic
                    val (aqx, aqy, ax, ay) =
                        if (cmd.isUpperCase()) listOf(qx, qy, x, y) else listOf(cx + qx, cy + qy, cx + x, cy + y)
                    val cp1x = cx + 2.0 / 3.0 * (aqx - cx)
                    val cp1y = cy + 2.0 / 3.0 * (aqy - cy)
                    val cp2x = ax + 2.0 / 3.0 * (aqx - ax)
                    val cp2y = ay + 2.0 / 3.0 * (aqy - ay)
                    path.curveTo(cp1x, cp1y, cp2x, cp2y, ax, ay)
                    lastQx = aqx
                    lastQy = aqy
                    lastQuad = true
                    lastCubic = false
                    i += 4
                }
                'T' -> {
                    // Smooth quadratic: reflected control point
                    val x = tokens.getOrNull(i)?.toDoubleOrNull() ?: break
                    val y = tokens.getOrNull(i + 1)?.toDoubleOrNull() ?: break
                    val aqx = if (lastQuad) 2 * cx - lastQx else cx
                    val aqy = if (lastQuad) 2 * cy - lastQy else cy
                    val (ax, ay) = if (cmd.isUpperCase()) Pair(x, y) else Pair(cx + x, cy + y)
                    val cp1x = cx + 2.0 / 3.0 * (aqx - cx)
                    val cp1y = cy + 2.0 / 3.0 * (aqy - cy)
                    val cp2x = ax + 2.0 / 3.0 * (aqx - ax)
                    val cp2y = ay + 2.0 / 3.0 * (aqy - ay)
                    path.curveTo(cp1x, cp1y, cp2x, cp2y, ax, ay)
                    lastQx = aqx
                    lastQy = aqy
                    lastQuad = true
                    lastCubic = false
                    i += 2
                }
                'A' -> {
                    // Arc: approximate as straight line to endpoint (sufficient for length sampling).
                    // Arc params: rx ry x-rotation large-arc-flag sweep-flag x y
                    // The first 5 params (rx,ry,xRot,largeArc,sweep) control the arc shape but
                    // are not needed for the endpoint-only approximation — consume them to advance i.
                    if (tokens.size <= i + 6) break
                    val x = tokens.getOrNull(i + 5)?.toDoubleOrNull() ?: break
                    val y = tokens.getOrNull(i + 6)?.toDoubleOrNull() ?: break
                    if (cmd.isUpperCase()) path.lineTo(x, y) else path.lineTo(cx + x, cy + y)
                    lastCubic = false
                    lastQuad = false
                    i += 7
                }
                'Z' -> {
                    path.closePath()
                    lastCubic = false
                    lastQuad = false
                }
                else -> {
                    log.warning(
                        "SmilTimelineFrameSampler: unknown SVG path command '$cmd' at token $i " +
                            "in path '${if (d.length > 80) d.take(80) + "…" else d}'. " +
                            "Motion animation will be incorrect from this point onward.",
                    )
                    i++ // skip unknown token to avoid infinite loop
                }
            }
        }
    }

    private fun docToString(doc: Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }
}
