package dev.kuml.io.anim

import dev.kuml.render.smil.SmilTimeline
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.bridge.BridgeContext
import org.apache.batik.bridge.GVTBuilder
import org.apache.batik.bridge.UserAgentAdapter
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import org.apache.batik.util.XMLResourceDescriptor
import org.w3c.dom.Document
import java.awt.Color
import java.io.StringReader
import java.io.StringWriter
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Samples an SMIL-animated SVG into static PNG frames by freezing the Batik animation clock.
 *
 * ## How it works
 *
 * 1. Parse the SVG string into a [Document] via Batik's SAXSVGDocumentFactory.
 * 2. Build a GVT tree with [BridgeContext] in DYNAMIC mode — this instantiates the
 *    SVGAnimationEngine on the document.
 * 3. For each frame i at time t = i * intervalMs / 1000.0 seconds:
 *    - Call `animationEngine.setCurrentTime(t)` to advance the SMIL clock.
 *      Batik applies all SMIL sandwich values to the live DOM at time t.
 *    - Serialize the live DOM to an SVG string (capturing the instantaneous state).
 *    - Rasterise the static SVG string via [PNGTranscoder] (no animation engine needed).
 * 4. Return the list of PNG byte arrays.
 *
 * ## Why serialize + re-transcode?
 *
 * Passing the live animated `Document` to a new `PNGTranscoder` fails because the new
 * transcoder's `BridgeContext` is in STATIC mode — it encounters `<animate>` elements
 * and crashes trying to access uninitialized animation structures. By serializing the
 * DOM to a string first, we get a pure-static SVG that PNGTranscoder handles correctly.
 *
 * ## Performance
 *
 * [TransformerFactory] is created once per [sample] call (not per frame): the factory
 * is thread-safe after construction and the [javax.xml.transform.Transformer] instances
 * it produces are NOT thread-safe, so we create one Transformer per frame but reuse the
 * factory. Creating a new [TransformerFactory] on every frame would trigger a service-loader
 * lookup on each call — 500 lookups for a max-length animation.
 *
 * ## DoS guards
 *
 * Frame count is bounded by [FrameBudget] before this sampler is called.
 *
 * ## Headless
 *
 * `java.awt.headless=true` is set before any Batik call (required for CI without display).
 */
public object BatikFrameSampler {
    /**
     * Shared [TransformerFactory] instance.
     *
     * [TransformerFactory] is documented as thread-safe (the factory itself, not the
     * Transformer instances it produces). Caching it avoids a service-loader lookup on
     * every frame when sampling long animations (up to 500 frames at the default cap).
     */
    private val transformerFactory: TransformerFactory = TransformerFactory.newInstance()

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

        // Pre-parse SVG size check — reject oversized inputs before handing them to the
        // XML parser.  A crafted billion-laughs entity expansion can exhaust heap before
        // any frame-count cap fires; checking byte length is a cheap first-line guard.
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
        val ctx = buildDynamicGvt(doc)

        val animEngine =
            try {
                ctx.animationEngine
            } catch (e: Exception) {
                throw AnimEncoderException(
                    "Batik animation engine not available. " +
                        "Ensure batik-anim and batik-bridge are on the classpath. Cause: ${e.message}",
                    e,
                )
            }

        if (animEngine == null) {
            throw AnimEncoderException(
                "Batik SVGAnimationEngine is null after building GVT in DYNAMIC mode. " +
                    "This may indicate a Batik version incompatibility.",
            )
        }

        // Frame timestamps: i=0 samples t=0.0 s (first animation state); the last
        // frame samples at (frameCount-1) * intervalMs / 1000.0 s, which is
        // `totalDurationMs - intervalMs` ms — intentionally one interval before the
        // end of the animation.  This avoids a duplicate start/end frame in looping
        // animations (the frame at t=totalDuration would be identical to t=0 for a
        // perfectly looping SMIL animation).  The gap is one frame's worth of time
        // and is invisible to viewers.  See [SmilTimelineFrameSampler.sample] for
        // the same convention in the Batik-free fallback path.
        return (0 until budget.frameCount).map { i ->
            val timeSec = ((i.toLong() * budget.intervalMs) / 1000.0).toFloat()
            animEngine.setCurrentTime(timeSec)
            // Serialize the live DOM (with animation values applied) to a static SVG string,
            // then rasterise that string — avoids the PNGTranscoder/DYNAMIC bridge conflict.
            val staticSvg = documentToString(doc)
            rasteriseString(staticSvg, options)
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private fun parseSvgDocument(svg: String): Document {
        val parser = XMLResourceDescriptor.getXMLParserClassName()
        val factory = SAXSVGDocumentFactory(parser)
        // Harden the SAX parser against XXE and entity-expansion (billion-laughs) attacks.
        // These system properties are recognised by the Apache Xerces parser that Batik
        // bundles (org.apache.xerces.parsers.SAXParser) and by the JDK's built-in SAX
        // parser used as the fallback.  We set them before every createDocument() call
        // because SAXSVGDocumentFactory instantiates a new parser per invocation.
        System.setProperty(
            "org.xml.sax.driver",
            "org.apache.xerces.parsers.SAXParser",
        )
        System.setProperty(
            "javax.xml.accessExternalDTD",
            "false",
        )
        // Limit entity expansion to 64 000 replacements — well above anything a legitimate
        // SVG needs, but orders of magnitude below a billion-laughs payload.
        System.setProperty(
            "jdk.xml.entityExpansionLimit",
            "64000",
        )
        System.setProperty(
            "jdk.xml.maxOccurLimit",
            "10000",
        )
        return try {
            factory.createDocument(
                "file:///kuml-anim.svg",
                StringReader(svg),
            )
        } catch (e: Exception) {
            throw AnimEncoderException("Failed to parse SVG document: ${e.message}", e)
        }
    }

    private fun buildDynamicGvt(doc: Document): BridgeContext {
        val userAgent = UserAgentAdapter()
        val ctx = BridgeContext(userAgent)
        ctx.setDynamicState(BridgeContext.DYNAMIC)
        val builder = GVTBuilder()
        try {
            builder.build(ctx, doc)
        } catch (e: Exception) {
            throw AnimEncoderException("Failed to build Batik GVT tree: ${e.message}", e)
        }
        return ctx
    }

    /** Serialise a DOM [Document] to an SVG string using the JDK XML transformer. */
    private fun documentToString(doc: Document): String {
        // Reuse the shared factory — createing a new TransformerFactory per frame triggers
        // an expensive service-loader lookup each time (500x for a max-length animation).
        // The Transformer itself is NOT thread-safe and must be created fresh per call.
        val transformer = transformerFactory.newTransformer()
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }

    /** Rasterise a static SVG string to PNG bytes via [PNGTranscoder]. */
    private fun rasteriseString(
        staticSvg: String,
        options: AnimRenderOptions,
    ): ByteArray {
        val transcoder = PNGTranscoder()
        transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, options.widthPx.toFloat())

        if (!options.transparent) {
            val bg =
                try {
                    parseColor(options.backgroundColor)
                } catch (_: Exception) {
                    Color.WHITE
                }
            transcoder.addTranscodingHint(PNGTranscoder.KEY_BACKGROUND_COLOR, bg)
        }

        val input = TranscoderInput(StringReader(staticSvg))
        // Use a size-capped stream so that a single large frame cannot exhaust heap.
        // The per-frame limit is set to maxSizeBytes / max(1, maxFrames) — i.e. the
        // total output budget divided evenly across frames — with a floor of 50 MiB so
        // that a single-frame export at the warn threshold is always allowed.
        val perFrameLimit =
            maxOf(options.maxSizeBytes / options.maxFrames.toLong(), 52_428_800L)
        val out = SizeLimitedByteArrayOutputStream(perFrameLimit)
        val output = TranscoderOutput(out)
        try {
            transcoder.transcode(input, output)
        } catch (e: SizeLimitExceededException) {
            val limitMb = perFrameLimit / (1024 * 1024)
            throw AnimEncoderException(
                "Single animation frame exceeds the $limitMb MiB per-frame limit. " +
                    "Reduce --width or diagram complexity.",
                e,
            )
        } catch (e: Exception) {
            throw AnimEncoderException("Batik rasterisation failed: ${e.message}", e)
        }
        return out.toByteArray()
    }

    /** Parse a CSS colour string to [Color]. Supports `"white"`, `"#rrggbb"`, `"#rgb"`. */
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
