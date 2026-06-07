package dev.kuml.io.latex.sysml2

import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.escapeLatex
import dev.kuml.io.latex.fmtCoord
import dev.kuml.kerml.KermlFeature
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActivityNodeKind
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.ConnectionDefinition
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.LifelineDefinition
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PortDefinition
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.UseCaseDefinition

/**
 * TikZ-Renderer für SysML-2 Definitionen — die LaTeX-Variante des
 * SVG-BDD-Renderers. Spiegelt das Compartment-Layout (Stereotyp + Name +
 * Features), nutzt die `kuml-class`/`kuml-classname`/`kuml-feature`-Styles
 * aus dem TikZ-Style-Block, den `KumlLatexRenderer` einmal pro Picture
 * emittiert.
 *
 * Stereotyp-Mapping pro Definition-Kind:
 *  - [PartDefinition] → `«part def»`
 *  - [AttributeDefinition] → `«attribute def»`
 *  - [PortDefinition] → `«port def»`
 *  - [ConnectionDefinition] → `«connection def»`
 *
 * Y-Flip-Konvention: Layout-Y zeigt nach unten, TikZ-Y nach oben — wir
 * negieren Y bei der Emission, sodass das BDD pixel-identisch zum SVG-Pendant
 * landet.
 */
internal object Sysml2DefLatexRenderer {
    fun render(
        definition: Sysml2Definition,
        nodeId: NodeId,
        layout: NodeLayout,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val stereotype =
            when (definition) {
                is PartDefinition -> "part def"
                is AttributeDefinition -> "attribute def"
                is PortDefinition -> "port def"
                is ConnectionDefinition -> "connection def"
                // V2.0.7: UC-Diagramm-Definitions. V2.0.7-MVP rendert sie als
                // Rechteck-mit-Label-Boxen mit semantischem Stereotyp; das
                // TikZ-Stickfigur-/Ellipsen-Pendant landet in V2.x (analog
                // zur BDD/IBD-Geschichte).
                is ActorDefinition -> "actor def"
                is UseCaseDefinition -> "use case def"
                // V2.0.8: REQ-Diagramm. V2.0.8-MVP rendert die Anforderung
                // als Rechteck-mit-Label-Box mit `«requirement»`-Stereotyp;
                // das dreikompartimentige TikZ-Pendant (Stereotyp + Name +
                // wort-gewrappter Anforderungstext) landet in V2.x, analog zur
                // BDD/IBD/UC-Geschichte.
                is RequirementDefinition -> "requirement"
                // V2.0.9: STM-Diagramm. V2.0.9-MVP rendert StateDefinitions
                // als Rechteck mit `«state»`-Stereotyp — abgerundete Ecken
                // und Pseudo-State-Markierungen (Initial-Kreis / Final-Donut)
                // im TikZ-Pendant sind V2.x-Polish, analog zur
                // BDD/IBD/UC/REQ-Geschichte. Der Stereotyp-String
                // unterscheidet sich je nach State-Kind, damit der visuelle
                // Hinweis im Fallback erhalten bleibt.
                is StateDefinition ->
                    when {
                        definition.isInitial -> "initial pseudo-state"
                        definition.isFinal -> "final pseudo-state"
                        else -> "state"
                    }
                // V2.0.10: ACT-Diagramm. V2.0.10-MVP rendert ActionDefinitions
                // als Rechteck mit kind-spezifischem Stereotyp; das
                // shape-spezifische TikZ-Pendant (abgerundete Rechtecke, Kreise,
                // Rauten, Bars) ist V2.x-Polish, analog zur BDD/IBD/UC/REQ/STM-
                // Geschichte. Der Stereotyp-String unterscheidet sich je nach
                // ActivityNodeKind, damit der visuelle Hinweis im Fallback
                // erhalten bleibt.
                is ActionDefinition ->
                    when (definition.kind) {
                        ActivityNodeKind.Action -> "action"
                        ActivityNodeKind.Initial -> "initial node"
                        ActivityNodeKind.Final -> "final node"
                        ActivityNodeKind.FlowFinal -> "flow final node"
                        ActivityNodeKind.Decision -> "decision node"
                        ActivityNodeKind.Merge -> "merge node"
                        ActivityNodeKind.Fork -> "fork node"
                        ActivityNodeKind.Join -> "join node"
                    }
                // V2.0.11: SEQ-Diagramm. V2.0.11-MVP rendert LifelineDefinitions
                // als Rechteck mit `«lifeline»`-Stereotyp; das axis-orientierte
                // TikZ-Pendant (Lifeline-Kopf + vertikale gestrichelte Zeit-Achse
                // + horizontale Nachrichten-Pfeile) erfordert einen separaten
                // TikZ-Pfad und ist V2.x-Polish, idealerweise via `pgf-umlsd`.
                is LifelineDefinition -> "lifeline"
                // V2.0.12: PAR-Diagramm. V2.0.12-MVP rendert ConstraintDefinitions
                // als Rechteck mit `«constraint»`-Stereotyp; das dreikompartimentige
                // TikZ-Pendant (Stereotyp + Name + Expression-Body + Parameter-Pin-
                // Liste) ist V2.x-Polish, analog zur BDD/IBD/UC/REQ/STM/ACT/SEQ-
                // Geschichte.
                is ConstraintDefinition -> "constraint"
            }
        renderBox(definition, stereotype, nodeId, layout, options, out)
    }

    private fun renderBox(
        definition: Sysml2Definition,
        stereotype: String,
        nodeId: NodeId,
        layout: NodeLayout,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val x = layout.bounds.origin.x
        val y = layout.bounds.origin.y
        val w = layout.bounds.size.width
        val h = layout.bounds.size.height
        val name = tikzId(nodeId)

        // Outer frame at the layout's top-left, with Y negated for the TikZ
        // axis flip (mirrors the UML class-box convention in this module).
        out.appendLine(
            "${options.indent}\\node[kuml-class, anchor=north west, " +
                "minimum width=${fmtCoord(w)}pt, minimum height=${fmtCoord(h)}pt] " +
                "($name) at (${fmtCoord(x)}pt, ${fmtCoord(-y)}pt) {};",
        )

        // Two- or three-compartment vertical layout depending on whether the
        // definition has any features.
        val hasFeatures = definition.features.isNotEmpty()
        val headerH = if (hasFeatures) h * 0.4f else h
        val featuresH = if (hasFeatures) h * 0.6f else 0f

        // Header: «kind» + name, centred in the header band.
        val cx = x + w / 2f
        val headerCy = y + headerH / 2f
        val nameStyle = if (definition.isAbstract) "kuml-classname-abstract" else "kuml-classname"
        val headerText =
            "\\begin{tabular}{c}" +
                "\\textit{\\small \\guillemotleft{}${escapeLatex(stereotype)}\\guillemotright{}}\\\\" +
                "\\textbf{${escapeLatex(definition.name)}}" +
                "\\end{tabular}"
        out.appendLine(
            "${options.indent}\\node[$nameStyle, anchor=center] at " +
                "(${fmtCoord(cx)}pt, ${fmtCoord(-headerCy)}pt) {$headerText};",
        )

        if (!hasFeatures) return

        // Divider between header and features.
        val dividerY = y + headerH
        out.appendLine(
            "${options.indent}\\draw[line width=0.4pt] " +
                "(${fmtCoord(x)}pt, ${fmtCoord(-dividerY)}pt) -- " +
                "(${fmtCoord(x + w)}pt, ${fmtCoord(-dividerY)}pt);",
        )

        // Feature lines, one per Feature in declaration order. Spacing is
        // proportional to the available band so even long feature lists
        // don't run out the bottom.
        val lineHeight = featuresH / (definition.features.size + 1).coerceAtLeast(2)
        for ((i, feature) in definition.features.withIndex()) {
            val ly = dividerY + lineHeight * (i + 1)
            out.appendLine(
                "${options.indent}\\node[kuml-feature, anchor=west] at " +
                    "(${fmtCoord(x + INNER_PAD)}pt, ${fmtCoord(-ly)}pt) " +
                    "{${escapeLatex(feature.formatBdd())}};",
            )
        }
    }

    /**
     * `name : Type [multiplicity] = default` — SysML-2-BDD Feature-Form.
     * Multiplicity wird weggelassen wenn `1`; Default wird angehängt wenn
     * vorhanden.
     */
    private fun KermlFeature.formatBdd(): String {
        val type = typeId ?: "?"
        val multSuffix = if (multiplicity.toSpecForm() == "1") "" else " [${multiplicity.toSpecForm()}]"
        val defaultSuffix = defaultExpression?.let { " = $it" } ?: ""
        return "$name : $type$multSuffix$defaultSuffix"
    }

    private fun tikzId(id: NodeId): String = "n_" + id.value.replace(Regex("[^A-Za-z0-9_]"), "_")

    private const val INNER_PAD: Float = 6f
}
