package dev.kuml.io.anim

import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
import dev.kuml.render.smil.SmilAnimation
import dev.kuml.render.smil.SmilTimeline
import dev.kuml.renderer.theme.core.KumlColor
import java.awt.geom.AffineTransform
import java.awt.geom.FlatteningPathIterator
import java.awt.geom.GeneralPath
import java.util.Locale
import java.util.logging.Logger

/**
 * Fallback frame sampler: reconstructs each frame by computing static attribute values
 * from [SmilTimeline] entries at time t and injecting them into the SVG string — no
 * DOM parsing, no Batik animation engine required.
 *
 * ## Why string-based instead of DOM?
 *
 * JAXP's DOM parser with `isNamespaceAware=true` re-prefixes SVG namespace declarations
 * when serialising back to XML (e.g. `<svg>` → `<ns0:svg xmlns:ns0="…">`). Batik's
 * `PNGTranscoder` does not recognise the re-prefixed elements and silently produces a
 * fully-transparent empty image. String manipulation avoids the round-trip entirely.
 *
 * ## Supported animation types
 *
 * - [SmilAnimation.Animate]: linear interpolation of a plain attribute (opacity, stroke-width, …).
 * - [SmilAnimation.AnimateTransform]: linear interpolation of numeric transform tokens.
 * - [SmilAnimation.AnimateMotion]: position along SVG path via [java.awt.geom] — sets cx/cy.
 * - [SmilAnimation.Set]: threshold apply (value applied once beginMs is reached).
 * - [SmilAnimation.Fill]: colour lerp for the active window then freeze.
 */
public object SmilTimelineFrameSampler {
    private val log: Logger = Logger.getLogger(SmilTimelineFrameSampler::class.java.name)

    // Self-closing SMIL animation elements (both inline and xlink:href form).
    // DOT_MATCHES_ALL so multi-line attributes are captured.
    private val SMIL_STRIP_PATTERN =
        Regex(
            """<(?:animate|animateTransform|animateMotion|set)\b[^>]*/>\n?""",
            RegexOption.DOT_MATCHES_ALL,
        )

    public fun sample(
        svg: String,
        timeline: SmilTimeline,
        budget: FrameBudget,
        options: AnimRenderOptions,
    ): List<ByteArray> {
        System.setProperty("java.awt.headless", "true")

        val svgBytes = svg.toByteArray(Charsets.UTF_8)
        if (svgBytes.size.toLong() > options.maxSvgBytes) {
            val sizeMb = svgBytes.size / (1024 * 1024)
            val maxMb = options.maxSvgBytes / (1024 * 1024)
            throw AnimEncoderException(
                "SVG input is $sizeMb MiB, exceeds $maxMb MiB limit (maxSvgBytes=${options.maxSvgBytes}).",
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

    internal fun buildStaticFrame(
        svg: String,
        timeline: SmilTimeline,
        tMs: Long,
    ): String {
        // Step 1: strip all SMIL animation elements from the SVG string.
        var result = SMIL_STRIP_PATTERN.replace(svg, "")

        // Step 2: SMIL sandwich resolution.
        //
        // Multiple animations frequently target the SAME (element, attribute) pair across
        // disjoint time windows — e.g. a highlight rect's `opacity` is animated 0→0.4 then
        // 0.4→0 once per replay cycle, and its `fill` is animated once per cycle too. SMIL
        // priority semantics say that at time t, the animation with the LATEST begin time
        // that has already started wins (all kUML animations use fill="freeze", so an ended
        // animation holds its `to` value until a later-begun one takes over).
        //
        // Applying every animation blindly in list order — as a naive loop would — lets a
        // not-yet-started animation overwrite the active one with its `from` value (e.g. a
        // later cycle's fill="#ffffff" start value masking the current cycle's "#ffd54a"),
        // which renders the highlight invisible. Grouping and picking the latest-started
        // animation per target reproduces correct SMIL behaviour.
        //
        // AnimateMotion is handled separately because it writes two attributes (cx + cy).

        val motions = timeline.animations.filterIsInstance<SmilAnimation.AnimateMotion>()
        val attrAnims = timeline.animations.filter { it !is SmilAnimation.AnimateMotion }

        // Attribute animations grouped by (elementId, target attribute name).
        val groups = attrAnims.groupBy { it.elementId to attrKeyOf(it) }
        for ((key, anims) in groups) {
            val (elementId, attrName) = key
            // The winning animation is the one with the latest begin time that has started.
            val chosen = anims.filter { tMs >= it.beginMs }.maxByOrNull { it.beginMs } ?: continue
            val value = attrValueOf(chosen, tMs) ?: continue
            result = setAttr(result, elementId, attrName, value)
        }

        // Motion animations grouped by target element.
        val motionGroups = motions.groupBy { it.elementId }
        for ((elementId, anims) in motionGroups) {
            val chosen = anims.filter { tMs >= it.beginMs }.maxByOrNull { it.beginMs } ?: continue
            val t = normalise(tMs, chosen.beginMs, chosen.durationMs, chosen.repeatCount).coerceIn(0f, 1f)
            val (x, y) = pointAlongPath(chosen.path, t.toDouble())
            result = setAttr(result, elementId, "cx", String.format(Locale.ROOT, "%.2f", x))
            result = setAttr(result, elementId, "cy", String.format(Locale.ROOT, "%.2f", y))
        }

        return result
    }

    /** The SVG attribute name a (non-motion) animation writes to. */
    private fun attrKeyOf(anim: SmilAnimation): String =
        when (anim) {
            is SmilAnimation.Animate -> anim.attribute
            is SmilAnimation.AnimateTransform -> "transform"
            is SmilAnimation.Set -> anim.attribute
            is SmilAnimation.Fill -> "fill"
            is SmilAnimation.AnimateMotion -> "__motion__" // not reached; motions handled separately
        }

    /**
     * The static value [anim] holds at [tMs], assuming it has already started
     * (`tMs >= anim.beginMs`). With fill="freeze" semantics an ended animation holds its
     * `to` value. Returns null only when no meaningful value applies.
     */
    private fun attrValueOf(
        anim: SmilAnimation,
        tMs: Long,
    ): String? =
        when (anim) {
            is SmilAnimation.Animate -> {
                val t = normalise(tMs, anim.beginMs, anim.durationMs, anim.repeatCount)
                if (t <= 1f) lerp(anim.from, anim.to, t) else anim.to
            }
            is SmilAnimation.AnimateTransform -> {
                val t = normalise(tMs, anim.beginMs, anim.durationMs, anim.repeatCount)
                val value = if (t <= 1f) lerp(anim.from, anim.to, t) else anim.to
                "${anim.type.svgToken}($value)"
            }
            is SmilAnimation.Set -> anim.to
            is SmilAnimation.Fill -> {
                val t = normalise(tMs, anim.beginMs, anim.durationMs)
                when {
                    t <= 1f -> lerp(anim.fromColor ?: "none", anim.color, t)
                    else -> anim.color
                }
            }
            is SmilAnimation.AnimateMotion -> null // handled separately
        }

    /**
     * In the SVG string, find the opening tag of any element whose `id` attribute equals
     * [elementId], then set [attribute] to [value] — replacing any existing value or
     * appending before the closing `>` / `/>`.
     *
     * This operates on the raw SVG string to avoid JAXP namespace re-prefixing issues.
     */
    private fun setAttr(
        svg: String,
        elementId: String,
        attribute: String,
        value: String,
    ): String {
        val idEsc = Regex.escape(elementId)
        val attrEsc = Regex.escape(attribute)
        val valueEsc = value.replace("&", "&amp;").replace("\"", "&quot;")

        // Match any opening SVG/XML tag that contains id="elementId".
        // The tag may span multiple lines (DOT_MATCHES_ALL) and the id attribute
        // can appear anywhere in the attribute list.
        val tagPattern =
            Regex(
                """(<[\w:]+(?:\s+[^>]*?)?\s+id="$idEsc"[^>]*?)(/>|>)""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            )

        return tagPattern.replace(svg) { mr ->
            val tagBody = mr.groupValues[1]
            val closing = mr.groupValues[2]
            // Replace existing attribute or append before the closing delimiter
            val existingAttr = Regex("""\s+$attrEsc="[^"]*"""")
            val newBody =
                if (existingAttr.containsMatchIn(tagBody)) {
                    existingAttr.replace(tagBody) { """ $attribute="$valueEsc"""" }
                } else {
                    """$tagBody $attribute="$valueEsc""""
                }
            "$newBody$closing"
        }
    }

    // ── Animation interpolation helpers ───────────────────────────────────────

    private fun normalise(
        tMs: Long,
        beginMs: Long,
        durationMs: Long,
        repeatCount: Int = SmilAnimation.REPEAT_ONCE,
    ): Float {
        if (durationMs <= 0L) return if (tMs >= beginMs) 1.0f else -1.0f
        val elapsed = tMs - beginMs
        if (elapsed < 0L) return (elapsed.toFloat() / durationMs).coerceIn(-1.0f, -0.0f)
        val effectiveElapsed =
            if (repeatCount == SmilAnimation.REPEAT_INDEFINITE) elapsed % durationMs else elapsed
        return (effectiveElapsed.toFloat() / durationMs).coerceIn(0.0f, 2.0f)
    }

    private fun lerp(
        from: String,
        to: String,
        t: Float,
    ): String {
        val clamped = t.coerceIn(0.0f, 1.0f)
        val fromNums = from.trim().split("\\s+".toRegex()).mapNotNull { it.toFloatOrNull() }
        val toNums = to.trim().split("\\s+".toRegex()).mapNotNull { it.toFloatOrNull() }
        if (fromNums.size == toNums.size && fromNums.isNotEmpty()) {
            return fromNums.zip(toNums).joinToString(" ") { (a, b) ->
                val v = a + (b - a) * clamped
                if (v == v.toLong().toFloat()) v.toLong().toString() else String.format(Locale.ROOT, "%.4f", v)
            }
        }
        val fc = parseHexColor(from)
        val tc = parseHexColor(to)
        if (fc != null && tc != null) {
            val r = (fc[0] + (tc[0] - fc[0]) * clamped).toInt().coerceIn(0, 255)
            val g = (fc[1] + (tc[1] - fc[1]) * clamped).toInt().coerceIn(0, 255)
            val b = (fc[2] + (tc[2] - fc[2]) * clamped).toInt().coerceIn(0, 255)
            return "#%02x%02x%02x".format(r, g, b)
        }
        return if (clamped < 1.0f) from else to
    }

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

    // ── SVG path traversal for AnimateMotion ──────────────────────────────────

    private fun pointAlongPath(
        d: String,
        t: Double,
    ): Pair<Double, Double> =
        try {
            val path = GeneralPath()
            parseSvgPathInto(d, path)
            val totalLength = pathLength(path.getPathIterator(AffineTransform()))
            pointAtLength(path.getPathIterator(AffineTransform()), totalLength * t.coerceIn(0.0, 1.0))
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
                java.awt.geom.PathIterator.SEG_CUBICTO -> {
                    // Approximate cubic Bezier length via FlatteningPathIterator (done by caller)
                    px = coords[4]
                    py = coords[5]
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
        val flat = FlatteningPathIterator(iter, 1.0)
        val coords = DoubleArray(6)
        var len = 0.0
        var px = 0.0
        var py = 0.0
        while (!flat.isDone) {
            when (flat.currentSegment(coords)) {
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
            flat.next()
        }
        return Pair(px, py)
    }

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
        var lastCx2 = 0.0
        var lastCy2 = 0.0
        var lastCubic = false
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
                    val aqx = if (cmd.isUpperCase()) qx else cx + qx
                    val aqy = if (cmd.isUpperCase()) qy else cy + qy
                    val ax = if (cmd.isUpperCase()) x else cx + x
                    val ay = if (cmd.isUpperCase()) y else cy + y
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
                    val x = tokens.getOrNull(i)?.toDoubleOrNull() ?: break
                    val y = tokens.getOrNull(i + 1)?.toDoubleOrNull() ?: break
                    val aqx = if (lastQuad) 2 * cx - lastQx else cx
                    val aqy = if (lastQuad) 2 * cy - lastQy else cy
                    val ax = if (cmd.isUpperCase()) x else cx + x
                    val ay = if (cmd.isUpperCase()) y else cy + y
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
                    log.warning("Unknown SVG path command '$cmd' at token $i")
                    i++
                }
            }
        }
    }
}
