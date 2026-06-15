package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlDiagram
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.Size
import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.UmlActivityEdge
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlInstanceSpecification
import dev.kuml.uml.UmlInstanceValue
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlLink
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.Visibility

/**
 * Content-aware [SizeProvider] for UML class diagrams (V2.0.44).
 *
 * The default [SizeProvider.constant] returns `160 × 80` which is too small
 * for any classifier with more than one attribute or operation — the SVG
 * renderer happily writes the feature lines below the box bottom or runs the
 * trailing characters past the box right edge.
 *
 * This provider walks the diagram once and pre-computes an
 * `elementId → Size` map. For each [UmlClass], [UmlInterface] or
 * [UmlComponent] it measures:
 *
 * - **Width**: max over the title, the `«stereo»` header, and every feature
 *   line (attributes + operations), using fixed per-character width estimates
 *   (no real font metrics — they live in the renderer). For components with
 *   ports an extra horizontal reserve is added so port labels don't collide
 *   with the title.
 * - **Height**: stereotype header + name line + divider + feature lines +
 *   bottom padding, matching the cumulative `cy` increments in
 *   [dev.kuml.io.svg.uml.renderUmlClass] etc.
 *
 * All other element kinds fall back to the constant size — keeps state-machine
 * vertices, use-case nodes, packages and partitions unchanged for V2.0.44.
 *
 * Char-width estimates are intentionally generous (~10 % padding) to absorb
 * proportional-font variance across themes (Plain, kUML, Elegant, Playful).
 *
 * Usage:
 * ```kotlin
 * val sizes = UmlContentSizeProvider(diagram)
 * val graph = UmlLayoutBridge.toLayoutGraph(diagram, sizes)
 * ```
 */
public class UmlContentSizeProvider
    @JvmOverloads
    constructor(
        diagram: KumlDiagram,
        private val layoutDirection: LayoutDirection = LayoutDirection.TopToBottom,
    ) : SizeProvider {
        /**
         * Anzahl ein-/ausgehender Relationship-Kanten pro Knoten-ID. Wird einmal
         * beim Konstruktor-Lauf berechnet und in [classSize] / [interfaceSize] /
         * [componentSize] als "Anschluss-Puffer" auf die Seitenlänge addiert, an
         * der die ELK-Layered-Engine voraussichtlich Kanten andocken lässt.
         *
         * Selbst-Loops zählen 2 (sie greifen an zwei Punkten derselben Box an).
         */
        private val connectionsById: Map<String, Int> =
            run {
                val out = mutableMapOf<String, Int>()
                countConnections(diagram.elements, out)
                out
            }

        private val byId: Map<String, Size> =
            run {
                val out = mutableMapOf<String, Size>()
                collect(diagram.elements, out)
                out
            }

        override fun sizeOf(
            elementId: String,
            elementKind: String,
        ): Size = byId[elementId] ?: Size(DEFAULT_W, DEFAULT_H)

        private fun collect(
            elements: Iterable<*>,
            out: MutableMap<String, Size>,
        ) {
            for (e in elements) {
                when (e) {
                    is UmlClass -> out[e.id] = classSize(e)
                    is UmlInterface -> out[e.id] = interfaceSize(e)
                    is UmlEnumeration -> out[e.id] = enumSize(e)
                    is UmlInstanceSpecification -> out[e.id] = instanceSize(e)
                    is UmlComponent -> {
                        out[e.id] = componentSize(e)
                        collect(e.nestedComponents, out)
                    }
                    is UmlPackage -> collect(e.members, out)
                    else -> {} // ignore — fall back to default
                }
            }
        }

        /**
         * Geht rekursiv durch [elements] und zählt für jeden in einem Edge-Element
         * auftauchenden Knoten ein Vorkommen. Self-Loops zählen 2, weil beide
         * Endpunkte an derselben Box andocken.
         */
        private fun countConnections(
            elements: Iterable<*>,
            out: MutableMap<String, Int>,
        ) {
            fun bump(id: String) {
                out[id] = (out[id] ?: 0) + 1
            }
            for (e in elements) {
                when (e) {
                    is UmlAssociation -> {
                        e.ends.forEach { bump(it.typeId) }
                    }
                    is UmlGeneralization -> {
                        bump(e.specificId)
                        bump(e.generalId)
                    }
                    is UmlInterfaceRealization -> {
                        bump(e.implementingId)
                        bump(e.interfaceId)
                    }
                    is UmlDependency -> {
                        bump(e.clientId)
                        bump(e.supplierId)
                    }
                    is UmlConnector -> {
                        bump(e.end1Id)
                        bump(e.end2Id)
                    }
                    is UmlInclude -> {
                        bump(e.baseId)
                        bump(e.additionId)
                    }
                    is UmlExtend -> {
                        bump(e.baseId)
                        bump(e.extensionId)
                    }
                    is UmlLink -> {
                        bump(e.sourceInstanceId)
                        bump(e.targetInstanceId)
                    }
                    is UmlActivityEdge -> {
                        bump(e.sourceId)
                        bump(e.targetId)
                    }
                    is UmlComponent -> countConnections(e.nestedComponents, out)
                    is UmlPackage -> countConnections(e.members, out)
                    else -> {}
                }
            }
        }

        /**
         * Anschluss-Puffer in px für [nodeId]. Die Wachstumsrichtung folgt der
         * voraussichtlichen Edge-Andock-Seite:
         *
         * - Layout fließt vertikal (`TopToBottom` / `BottomToTop`) →
         *   Edges andocken oben/unten → Puffer wandert in die **Breite**.
         * - Layout fließt horizontal (`LeftToRight` / `RightToLeft`) →
         *   Edges andocken links/rechts → Puffer wandert in die **Höhe**.
         *
         * Returns `Pair(widthExtra, heightExtra)`.
         */
        private fun connectionPuffer(nodeId: String): Pair<Float, Float> {
            val n = connectionsById[nodeId] ?: 0
            if (n == 0) return 0f to 0f
            val raw = (n * CONNECTION_PUFFER_PX).coerceAtMost(CONNECTION_PUFFER_MAX_PX)
            return when (layoutDirection) {
                LayoutDirection.TopToBottom,
                LayoutDirection.BottomToTop,
                -> raw to 0f
                LayoutDirection.LeftToRight,
                LayoutDirection.RightToLeft,
                -> 0f to raw
            }
        }

        // ── Size computations ─────────────────────────────────────────────────────

        private fun classSize(c: UmlClass): Size {
            val nameLine = c.name
            val stereoLine = stereoLabel(c.appliedStereotypes) ?: if (c.isAbstract) "«abstract»" else ""

            val attrLines = c.attributes.map { it.toFormattedLine() }
            val opLines = c.operations.map { it.toFormattedLine() }

            val w = boxWidth(nameLine, stereoLine, attrLines + opLines)
            val attrs = c.attributes.size
            val ops = c.operations.size
            val h = boxHeight(hasStereo = stereoLine.isNotEmpty(), attrs = attrs, ops = ops)
            val (wExtra, hExtra) = connectionPuffer(c.id)
            return Size(w + wExtra, h + hExtra)
        }

        /**
         * Enum-Größe — entspricht dem cy-Akkumulator in
         * [dev.kuml.io.svg.uml.renderUmlEnum]:
         *   start 18 (Top-Padding) + applied stereo header
         *   + 14 («enumeration»-Keyword) + 20 (Titel) + 6 (Gap)
         *   + 14 (Divider, falls Literals vorhanden)
         *   + literals × 13 + Bottom-Padding.
         *
         * Vor V3.0.11 fielen Enums durch das `when` in [collect] und bekamen den
         * `DEFAULT_W × DEFAULT_H` (160 × 80) Fallback — schon ab 3 Literals lief
         * die Liste unten aus der Box (siehe Vault-Note OrderStatus / CANCELLED).
         */
        private fun enumSize(e: UmlEnumeration): Size {
            val nameLine = e.name
            val stereoLine = stereoLabel(e.appliedStereotypes) ?: "«enumeration»"
            val literalLines = e.literals.map { it.name }
            val w = boxWidth(nameLine, stereoLine, literalLines)
            // Enum has «enumeration» keyword always → hasStereo = true.
            // Literals slot into the "attributes" compartment shape.
            val h = boxHeight(hasStereo = true, attrs = e.literals.size, ops = 0)
            val (wExtra, hExtra) = connectionPuffer(e.id)
            return Size(w + wExtra, h + hExtra)
        }

        /**
         * Instance-Größe für UmlInstanceSpecification (Object Diagram).
         *
         * Renderer-Konvention aus [dev.kuml.io.svg.uml.renderUmlInstance]:
         *   - Header `name : Classifier` (oder `: Classifier` anonym), unterstrichen.
         *   - Divider bei cy = 24 falls Slots existieren.
         *   - Pro Slot eine Zeile `featureName = value`, cy += 13.
         *   - InstanceRef-Slots werden als `featureName = → <instanceId>` gerendert —
         *     der Pfeil und die Instanz-ID müssen in die Breitenberechnung einfließen,
         *     sonst überläuft die Zeile (siehe Vault-Note Order Snapshot, V3.0.x-Bug).
         *
         * Vor diesem Fix landeten Instanzen im `else`-Zweig und erhielten den
         * `DEFAULT_W × DEFAULT_H` (160 × 80) Fallback — schon ein Slot wie
         * `customer = → alice@Customer` (≈ 178 px) lief rechts aus der Box.
         */
        private fun instanceSize(i: UmlInstanceSpecification): Size {
            val nameLine =
                if (i.name.isEmpty()) {
                    ": ${i.classifierName}"
                } else {
                    "${i.name} : ${i.classifierName}"
                }
            val slotLines =
                i.slots.map { slot ->
                    val rhs =
                        when (val v = slot.value) {
                            is UmlInstanceValue.Literal -> v.text
                            is UmlInstanceValue.InstanceRef -> "→ ${v.instanceId}"
                            is UmlInstanceValue.Null -> "null"
                        }
                    "${slot.featureName} = $rhs"
                }
            // Instance hat keine Stereotype-Zeile — Header ist nur der unterstrichene
            // Klassifikator-Bezug.
            val w = boxWidth(nameLine = nameLine, stereoLine = "", bodyLines = slotLines)
            val h = boxHeight(hasStereo = false, attrs = i.slots.size, ops = 0)
            val (wExtra, hExtra) = connectionPuffer(i.id)
            return Size(w + wExtra, h + hExtra)
        }

        private fun interfaceSize(i: UmlInterface): Size {
            val nameLine = i.name
            val stereoLine = stereoLabel(i.appliedStereotypes) ?: "«interface»"

            val opLines = i.operations.map { it.toFormattedLine() }
            val attrLines = i.attributes.map { it.toFormattedLine() }

            val w = boxWidth(nameLine, stereoLine, attrLines + opLines)
            val h = boxHeight(hasStereo = true, attrs = i.attributes.size, ops = i.operations.size)
            val (wExtra, hExtra) = connectionPuffer(i.id)
            return Size(w + wExtra, h + hExtra)
        }

        private fun componentSize(c: UmlComponent): Size {
            val nameLine = c.name
            val stereoLine = stereoLabel(c.appliedStereotypes) ?: ""

            val attrLines = c.attributes.map { it.toFormattedLine() }
            val opLines = c.operations.map { it.toFormattedLine() }

            var w = boxWidth(nameLine, stereoLine, attrLines + opLines)
            // Reserve horizontal space for port labels (port square 12px + label gap)
            // V2.0.45 — Ports alternate between left (even index) and right (odd index)
            // sides, and INSIDE-labels sit on the same horizontal row as the centred
            // title. The previous one-sided `maxOf(maxPortLabel) + PORT_RESERVE`
            // formula was too narrow whenever both sides carried a port: the title
            // text bled over the right-port label (see AUTOSAR engine-control bug).
            // Compute left/right label maxima separately and add BOTH plus a gap
            // around the title so labels and title coexist on the same row.
            if (c.ports.isNotEmpty()) {
                val leftPorts = c.ports.filterIndexed { idx, _ -> idx % 2 == 0 }
                val rightPorts = c.ports.filterIndexed { idx, _ -> idx % 2 == 1 }
                val maxLeftLabel = leftPorts.maxOfOrNull { estimateLabelWidth(it.name, SMALL_CHAR_PX) } ?: 0
                val maxRightLabel = rightPorts.maxOfOrNull { estimateLabelWidth(it.name, SMALL_CHAR_PX) } ?: 0
                val portReserve = maxLeftLabel + maxRightLabel + PORT_RESERVE * 2f
                w += portReserve
            }

            val hasFeatures = c.attributes.isNotEmpty() || c.operations.isNotEmpty()
            // Component always has «component» line + optional applied stereo line.
            val h =
                COMP_HEADER_H +
                    (if (stereoLine.isNotEmpty()) STEREO_LINE_H else 0f) +
                    NAME_LINE_H +
                    (if (hasFeatures) DIVIDER_GAP + (c.attributes.size + c.operations.size) * FEATURE_LINE_H + DIVIDER_GAP else 0f) +
                    BOX_BOTTOM_PAD
            val (wExtra, hExtra) = connectionPuffer(c.id)
            return Size(w + wExtra, maxOf(h, DEFAULT_H) + hExtra)
        }

        // ── Formatting helpers (mirrors UmlFormatHelpers.kt, kept local to bridge) ──

        private fun UmlProperty.toFormattedLine(): String {
            val vis =
                when (visibility) {
                    Visibility.PUBLIC -> "+"
                    Visibility.PRIVATE -> "-"
                    Visibility.PROTECTED -> "#"
                    Visibility.PACKAGE -> "~"
                }
            val stereoPrefix =
                if (appliedStereotypes.isNotEmpty()) {
                    "«${appliedStereotypes.joinToString(", ") { it.stereotypeName }}» "
                } else {
                    ""
                }
            return "$stereoPrefix$vis $name: ${type.name}"
        }

        private fun UmlOperation.toFormattedLine(): String {
            val vis =
                when (visibility) {
                    Visibility.PUBLIC -> "+"
                    Visibility.PRIVATE -> "-"
                    Visibility.PROTECTED -> "#"
                    Visibility.PACKAGE -> "~"
                }
            val params =
                parameters.joinToString(", ") { p -> "${p.name}: ${p.type.name}" }
            val ret = returnType?.name?.let { ": $it" } ?: ""
            val stereoPrefix =
                if (appliedStereotypes.isNotEmpty()) {
                    "«${appliedStereotypes.joinToString(", ") { it.stereotypeName }}» "
                } else {
                    ""
                }
            return "$stereoPrefix$vis $name($params)$ret"
        }

        private fun stereoLabel(applied: List<AppliedStereotype>): String? =
            if (applied.isEmpty()) {
                null
            } else {
                "«" + applied.joinToString(", ") { it.stereotypeName } + "»"
            }

        private fun boxWidth(
            nameLine: String,
            stereoLine: String,
            bodyLines: List<String>,
        ): Float {
            val titleW = estimateLabelWidth(nameLine, charPx = TITLE_CHAR_PX)
            val stereoW = if (stereoLine.isEmpty()) 0 else estimateLabelWidth(stereoLine, charPx = STEREO_CHAR_PX)
            val bodyMax = bodyLines.maxOfOrNull { estimateLabelWidth(it, charPx = BODY_CHAR_PX) } ?: 0
            val raw = maxOf(titleW, stereoW, bodyMax)
            return maxOf(DEFAULT_W, raw.toFloat() + BOX_H_PADDING)
        }

        private fun boxHeight(
            hasStereo: Boolean,
            attrs: Int,
            ops: Int,
        ): Float {
            val hasCompartments = attrs > 0 || ops > 0
            val attrCompartment = if (attrs > 0) DIVIDER_GAP + attrs * FEATURE_LINE_H else 0f
            val opsCompartment = if (ops > 0) (if (attrs > 0) DIVIDER_GAP else DIVIDER_GAP) + ops * FEATURE_LINE_H else 0f
            val h =
                (if (hasStereo) STEREO_LINE_H else 0f) +
                    NAME_LINE_H +
                    (if (hasCompartments) 0f else 0f) +
                    attrCompartment + opsCompartment +
                    BOX_BOTTOM_PAD
            return maxOf(h, DEFAULT_H)
        }

        private fun estimateLabelWidth(
            text: String,
            charPx: Float = BODY_CHAR_PX,
        ): Int = (text.length * charPx).toInt()

        public companion object {
            // ── Defaults (match UmlLayoutBridge fallback) ─────────────────────────
            public const val DEFAULT_W: Float = 160f
            public const val DEFAULT_H: Float = 80f

            // ── Vertical line metrics — match cy increments in the SVG renderers ─
            public const val STEREO_LINE_H: Float = 18f
            public const val NAME_LINE_H: Float = 20f
            public const val FEATURE_LINE_H: Float = 13f
            public const val DIVIDER_GAP: Float = 14f
            public const val BOX_BOTTOM_PAD: Float = 12f

            /** Component-glyph header reserve (two stacked 6px rects + top padding). */
            public const val COMP_HEADER_H: Float = 4f

            // ── Horizontal char-width estimates ───────────────────────────────────

            /** Avg width of a 14pt bold sans-serif char (incl. ~10% slack). */
            public const val TITLE_CHAR_PX: Float = 8.4f

            /** Avg width of an 11pt regular sans-serif char. */
            public const val BODY_CHAR_PX: Float = 6.6f

            /** Avg width of a 10pt italic sans-serif char (stereotype label). */
            public const val STEREO_CHAR_PX: Float = 5.6f

            /** Avg width of a 9pt sans-serif char (small / port label). */
            public const val SMALL_CHAR_PX: Float = 5.4f

            /** Horizontal padding (left + right) inside a box. */
            public const val BOX_H_PADDING: Float = 24f

            /** Extra reserve so port labels can sit inside without overlapping the title. */
            public const val PORT_RESERVE: Float = 12f

            // ── Connection-aware sizing (V2.x — siehe CLAUDE.md "Renderer-Sizing-Heuristik") ─

            /**
             * Zusätzliche Pixel pro ein-/ausgehender Kante, addiert auf der Seite,
             * an der ELK Kanten andocken lässt. 14 px liegt in der Mitte des
             * 12–16-px-Bands aus der Heuristik und entspricht ungefähr dem
             * horizontalen Abstand, den ELK pro Edge-Endpunkt am Knotenrand
             * reserviert.
             */
            public const val CONNECTION_PUFFER_PX: Float = 14f

            /**
             * Obergrenze für den Anschluss-Puffer pro Seite. Verhindert, dass
             * Hub-Klassen wie `BankUsers` (20+ FKs) absurd groß werden.
             * 200 px ≈ 14 Kanten — ab da gewinnt nur noch ELK über mehr Layer.
             */
            public const val CONNECTION_PUFFER_MAX_PX: Float = 200f
        }
    }
