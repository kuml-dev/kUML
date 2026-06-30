package dev.kuml.io.anim

import dev.kuml.render.smil.SmilTimeline
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.bridge.BridgeContext
import org.apache.batik.bridge.GVTBuilder
import org.apache.batik.bridge.UserAgentAdapter
import org.apache.batik.util.XMLResourceDescriptor
import org.w3c.dom.Document
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.StringReader
import javax.imageio.ImageIO

/**
 * Samples an SMIL-animated SVG into static PNG frames by advancing the Batik GVT
 * animation clock and painting the GVT tree directly to a [BufferedImage] at each step.
 *
 * ## How it works
 *
 * 1. Parse the SVG string into a [Document] via Batik's SAXSVGDocumentFactory.
 * 2. Build a GVT tree with [BridgeContext] in DYNAMIC mode — this instantiates the
 *    SVGAnimationEngine on the document.
 * 3. For each frame i at time t = i * intervalMs / 1000.0 seconds:
 *    - Call `animationEngine.setCurrentTime(t)` to advance the SMIL clock.
 *      Batik applies all SMIL sandwich values to the live GVT tree at time t.
 *    - Paint the root [org.apache.batik.gvt.GraphicsNode] directly onto a
 *      [BufferedImage] scaled to [AnimRenderOptions.widthPx].
 *    - Encode the [BufferedImage] to PNG bytes via [ImageIO].
 * 4. Return the list of PNG byte arrays.
 *
 * ## Why paint GVT directly?
 *
 * Batik's animation engine mutates the **GVT presentation values** (the visual state used
 * for painting), NOT the underlying DOM attributes. Serialising the DOM with a Transformer
 * and re-transcoding yields the original static attribute values for every frame — the
 * animation is invisible in the output. Painting the GVT directly captures the animated
 * state that `setCurrentTime` applied.
 *
 * ## DoS guards
 *
 * Frame count is bounded by [FrameBudget] before this sampler is called.
 *
 * ## Headless
 *
 * `java.awt.headless=true` is set before any Batik or AWT call (required for CI).
 */
public object BatikFrameSampler {
    /**
     * Sample [svg] into PNG frames as specified by [budget] and [options].
     *
     * @param svg SMIL-animated SVG string.
     * @param timeline The [SmilTimeline] (used for context; budget is already computed).
     * @param budget Frame count, interval, and effective fps.
     * @param options Export options (widthPx, transparent, backgroundColor).
     * @return List of single-frame PNG byte arrays, length == [FrameBudget.frameCount].
     * @throws AnimEncoderException if Batik fails to parse or rasterise the SVG.
     */
    public fun sample(
        svg: String,
        @Suppress("UNUSED_PARAMETER") timeline: SmilTimeline,
        budget: FrameBudget,
        options: AnimRenderOptions,
    ): List<ByteArray> {
        System.setProperty("java.awt.headless", "true")

        // Pre-parse size check — reject oversized inputs before the XML parser sees them.
        val svgBytes = svg.toByteArray(Charsets.UTF_8)
        if (svgBytes.size.toLong() > options.maxSvgBytes) {
            val sizeMb = svgBytes.size / (1024 * 1024)
            val maxMb = options.maxSvgBytes / (1024 * 1024)
            throw AnimEncoderException(
                "SVG input is $sizeMb MiB, which exceeds the $maxMb MiB limit " +
                    "(maxSvgBytes=${options.maxSvgBytes}). Reduce diagram complexity.",
            )
        }

        val doc = parseSvgDocument(svg)

        // Determine natural SVG dimensions from the viewBox attribute.
        val (naturalW, naturalH) = svgDimensions(doc, svg)
        val widthPx = options.widthPx
        val heightPx = if (naturalW > 0f) (naturalH * widthPx / naturalW).toInt().coerceAtLeast(1) else widthPx

        // Build GVT in DYNAMIC mode — this activates the SVGAnimationEngine.
        val userAgent = UserAgentAdapter()
        val ctx = BridgeContext(userAgent)
        ctx.setDynamicState(BridgeContext.DYNAMIC)
        val builder = GVTBuilder()
        val rootGvt =
            try {
                builder.build(ctx, doc)
            } catch (e: Exception) {
                throw AnimEncoderException("Failed to build Batik GVT tree: ${e.message}", e)
            }

        val animEngine =
            ctx.animationEngine
                ?: throw AnimEncoderException(
                    "Batik SVGAnimationEngine is null after building GVT in DYNAMIC mode. " +
                        "Ensure batik-anim is on the classpath.",
                )

        val bgColor = resolveBackground(options)
        val scale = widthPx.toDouble() / naturalW.toDouble()

        // Frame timestamps: i=0 → t=0.0 s; last frame → (frameCount-1)*intervalMs ms.
        // Stopping one interval before totalDuration avoids a duplicate start/end frame
        // in perfectly looping animations.
        return (0 until budget.frameCount).map { i ->
            val timeSec = (i.toLong() * budget.intervalMs / 1000.0).toFloat()
            animEngine.setCurrentTime(timeSec)
            paintFrame(rootGvt, widthPx, heightPx, scale, bgColor, options)
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private fun parseSvgDocument(svg: String): Document {
        System.setProperty("javax.xml.accessExternalDTD", "false")
        System.setProperty("jdk.xml.entityExpansionLimit", "64000")
        System.setProperty("jdk.xml.maxOccurLimit", "10000")

        val parser = XMLResourceDescriptor.getXMLParserClassName()
        val factory = SAXSVGDocumentFactory(parser)
        return try {
            factory.createDocument("file:///kuml-anim.svg", StringReader(svg))
        } catch (e: Exception) {
            throw AnimEncoderException("Failed to parse SVG document: ${e.message}", e)
        }
    }

    /**
     * Returns (width, height) of the SVG in user units.
     *
     * Priority: `viewBox` attribute → `width`/`height` attributes → fallback 800×600.
     */
    private fun svgDimensions(
        doc: Document,
        svg: String,
    ): Pair<Float, Float> {
        val root = doc.documentElement
        val viewBox = root?.getAttribute("viewBox")?.trim()
        if (!viewBox.isNullOrEmpty()) {
            val parts = viewBox.split(Regex("\\s+|,")).mapNotNull { it.toFloatOrNull() }
            if (parts.size >= 4 && parts[2] > 0f && parts[3] > 0f) {
                return parts[2] to parts[3]
            }
        }
        val wAttr = root?.getAttribute("width")?.trim()?.toFloatOrNull()
        val hAttr = root?.getAttribute("height")?.trim()?.toFloatOrNull()
        if (wAttr != null && hAttr != null && wAttr > 0f && hAttr > 0f) {
            return wAttr to hAttr
        }
        // Fallback — parse from raw SVG string (handles percentage-based viewBox)
        val vbMatch =
            Regex("""viewBox=["']([^"']+)["']""").find(svg)
        val vbParts =
            vbMatch
                ?.groupValues
                ?.get(1)
                ?.split(Regex("\\s+|,"))
                ?.mapNotNull { it.toFloatOrNull() }
        if (vbParts != null && vbParts.size >= 4 && vbParts[2] > 0f && vbParts[3] > 0f) {
            return vbParts[2] to vbParts[3]
        }
        return 800f to 600f
    }

    private fun resolveBackground(options: AnimRenderOptions): Color? =
        if (options.transparent) {
            null
        } else {
            parseColor(options.backgroundColor)
        }

    /**
     * Paint [rootGvt] at the current animation time onto a new [BufferedImage] and
     * encode it to PNG bytes.
     */
    private fun paintFrame(
        rootGvt: org.apache.batik.gvt.GraphicsNode,
        widthPx: Int,
        heightPx: Int,
        scale: Double,
        bgColor: Color?,
        options: AnimRenderOptions,
    ): ByteArray {
        val imageType = if (options.transparent) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val image = BufferedImage(widthPx, heightPx, imageType)
        val g2d = image.createGraphics()
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            if (bgColor != null) {
                g2d.color = bgColor
                g2d.fillRect(0, 0, widthPx, heightPx)
            }

            g2d.transform(AffineTransform.getScaleInstance(scale, scale))
            rootGvt.paint(g2d)
        } finally {
            g2d.dispose()
        }

        val perFrameLimit = maxOf(options.maxSizeBytes / options.maxFrames.toLong(), 52_428_800L)
        val out = SizeLimitedByteArrayOutputStream(perFrameLimit)
        try {
            ImageIO.write(image, "PNG", out)
        } catch (e: SizeLimitExceededException) {
            val limitMb = perFrameLimit / (1024 * 1024)
            throw AnimEncoderException(
                "Single animation frame exceeds the $limitMb MiB per-frame limit. " +
                    "Reduce --width or diagram complexity.",
                e,
            )
        } catch (e: Exception) {
            throw AnimEncoderException("Frame rasterisation failed: ${e.message}", e)
        }
        return out.toByteArray()
    }

    private fun parseColor(css: String): Color =
        when (css.lowercase().trim()) {
            "white" -> Color.WHITE
            "black" -> Color.BLACK
            "transparent" -> Color(0, 0, 0, 0)
            else -> {
                val hex = css.trimStart('#')
                when (hex.length) {
                    3 -> {
                        val r = hex[0].toString().repeat(2).toInt(16)
                        val g = hex[1].toString().repeat(2).toInt(16)
                        val b = hex[2].toString().repeat(2).toInt(16)
                        Color(r, g, b)
                    }
                    6 -> Color(hex.toInt(16))
                    else -> Color.WHITE
                }
            }
        }
}
