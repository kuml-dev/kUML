package dev.kuml.cli.structurizr

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4DeploymentNode
import dev.kuml.c4.model.C4Element
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.ComponentDiagram
import dev.kuml.c4.model.ContainerDiagram
import dev.kuml.c4.model.DeploymentDiagram
import dev.kuml.c4.model.SystemContextDiagram
import dev.kuml.c4.model.SystemLandscapeDiagram

/**
 * V1.1 — Structurizr-DSL-Emitter (Export-Richtung).
 *
 * Inverser Schritt zu [StructurizrDslParser] + [KumlDslGenerator]: nimmt
 * ein [C4Model] und schreibt es als Structurizr-DSL-`workspace`-Block.
 *
 * Stützt alle vier C4-Level (Persons, SoftwareSystems mit Containern,
 * Containers mit Components, DeploymentNodes mit Verschachtelung) und die
 * fünf von [StructurizrDslParser] gelesenen View-Typen (SystemContext,
 * Container, Component, Deployment, SystemLandscape). Dynamische Views
 * (Sequenzen) sind ausserhalb des Parser-Scopes — werden hier ebenfalls
 * weggelassen, mit einem Kommentar im Output.
 *
 * Identifier werden direkt aus [C4Element.id] übernommen und für
 * Structurizr nach den DSL-Regeln **sanitisiert** (nur Buchstaben/
 * Ziffern/`_`; Leerzeichen werden zu `_`, Doppel-Underscores reduziert).
 */
internal object StructurizrEmitter {
    /** Schreibt das vollständige `workspace { … }` als DSL-String. */
    fun emit(model: C4Model): String {
        val sb = StringBuilder()
        val workspaceName = model.name
        val workspaceDesc = model.description

        sb.append("workspace \"").append(escape(workspaceName)).append("\"")
        if (workspaceDesc != null) sb.append(" \"").append(escape(workspaceDesc)).append("\"")
        sb.appendLine(" {")
        sb.appendLine()

        // Identifier-Strategie: hierarchisch, damit Sub-Container Punkt-Notation nutzen können.
        sb.appendLine("    !identifiers hierarchical")
        sb.appendLine()

        sb.append(emitModelBlock(model, indent = "    "))
        sb.appendLine()
        sb.append(emitViewsBlock(model, indent = "    "))

        sb.appendLine("}")
        return sb.toString()
    }

    // ── model block ──────────────────────────────────────────────────────────

    private fun emitModelBlock(
        model: C4Model,
        indent: String,
    ): String {
        val sb = StringBuilder()
        sb.append(indent).appendLine("model {")
        val inner = "$indent    "

        // Top-level elements: persons + software systems first
        val persons = model.elements.filterIsInstance<C4Person>()
        for (p in persons) sb.append(emitPerson(p, inner))

        val systems = model.elements.filterIsInstance<C4SoftwareSystem>()
        for (s in systems) sb.append(emitSoftwareSystem(s, model, inner))

        // Top-level deployment nodes (have no parent in our flat model — those whose id is not in any children list)
        val allDnIds =
            model.elements
                .filterIsInstance<C4DeploymentNode>()
                .map { it.id }
                .toSet()
        val childDnIds =
            model.elements
                .filterIsInstance<C4DeploymentNode>()
                .flatMap { it.children }
                .toSet()
        val topLevelDns = model.elements.filterIsInstance<C4DeploymentNode>().filter { it.id !in childDnIds || it.id !in allDnIds }
        for (dn in topLevelDns) sb.append(emitDeploymentNode(dn, model, inner))

        // Relationships at the end of the model block
        if (model.relationships.isNotEmpty()) {
            sb.appendLine()
            for (r in model.relationships) sb.append(emitRelationship(r, inner))
        }

        sb.append(indent).appendLine("}")
        return sb.toString()
    }

    private fun emitPerson(
        p: C4Person,
        indent: String,
    ): String {
        val sb = StringBuilder()
        sb.append(indent)
        sb.append(sanitizeId(p.id)).append(" = person ")
        sb.append("\"").append(escape(p.name)).append("\"")
        if (p.description != null) sb.append(" \"").append(escape(p.description!!)).append("\"")
        if (p.external) sb.append(" \"External\"")
        sb.appendLine()
        return sb.toString()
    }

    private fun emitSoftwareSystem(
        s: C4SoftwareSystem,
        model: C4Model,
        indent: String,
    ): String {
        val sb = StringBuilder()
        sb.append(indent)
        sb.append(sanitizeId(s.id)).append(" = softwareSystem ")
        sb.append("\"").append(escape(s.name)).append("\"")
        if (s.description != null) sb.append(" \"").append(escape(s.description!!)).append("\"")
        if (s.external) sb.append(" \"External\"")

        // Open child block when there are containers
        val containers = model.elements.filterIsInstance<C4Container>().filter { it.system == s.id }
        if (containers.isNotEmpty()) {
            sb.appendLine(" {")
            val inner = "$indent    "
            for (c in containers) sb.append(emitContainer(c, model, inner))
            sb.append(indent).appendLine("}")
        } else {
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun emitContainer(
        c: C4Container,
        model: C4Model,
        indent: String,
    ): String {
        val sb = StringBuilder()
        sb.append(indent)
        sb.append(sanitizeId(c.id)).append(" = container ")
        sb.append("\"").append(escape(c.name)).append("\"")
        val desc = c.description ?: ""
        sb.append(" \"").append(escape(desc)).append("\"")
        if (c.technology != null) sb.append(" \"").append(escape(c.technology!!)).append("\"")

        val components = model.elements.filterIsInstance<C4Component>().filter { it.container == c.id }
        if (components.isNotEmpty()) {
            sb.appendLine(" {")
            val inner = "$indent    "
            for (comp in components) sb.append(emitComponent(comp, inner))
            sb.append(indent).appendLine("}")
        } else {
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun emitComponent(
        comp: C4Component,
        indent: String,
    ): String {
        val sb = StringBuilder()
        sb.append(indent)
        sb.append(sanitizeId(comp.id)).append(" = component ")
        sb.append("\"").append(escape(comp.name)).append("\"")
        val desc = comp.description ?: ""
        sb.append(" \"").append(escape(desc)).append("\"")
        if (comp.technology != null) sb.append(" \"").append(escape(comp.technology!!)).append("\"")
        sb.appendLine()
        return sb.toString()
    }

    private fun emitDeploymentNode(
        dn: C4DeploymentNode,
        model: C4Model,
        indent: String,
    ): String {
        val sb = StringBuilder()
        sb.append(indent)
        sb.append(sanitizeId(dn.id)).append(" = deploymentNode ")
        sb.append("\"").append(escape(dn.name)).append("\"")
        val desc = dn.description ?: ""
        sb.append(" \"").append(escape(desc)).append("\"")
        if (dn.technology != null) sb.append(" \"").append(escape(dn.technology!!)).append("\"")

        val childNodes =
            dn.children.mapNotNull { childId ->
                model.elements.filterIsInstance<C4DeploymentNode>().firstOrNull { it.id == childId }
            }
        if (childNodes.isNotEmpty()) {
            sb.appendLine(" {")
            val inner = "$indent    "
            for (child in childNodes) sb.append(emitDeploymentNode(child, model, inner))
            sb.append(indent).appendLine("}")
        } else {
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun emitRelationship(
        r: C4Relationship,
        indent: String,
    ): String {
        val sb = StringBuilder()
        sb.append(indent)
        sb.append(sanitizeId(r.source)).append(" -> ").append(sanitizeId(r.target))
        sb.append(" \"").append(escape(r.label)).append("\"")
        if (r.technology != null) sb.append(" \"").append(escape(r.technology!!)).append("\"")
        sb.appendLine()
        return sb.toString()
    }

    // ── views block ──────────────────────────────────────────────────────────

    private fun emitViewsBlock(
        model: C4Model,
        indent: String,
    ): String {
        val sb = StringBuilder()
        sb.append(indent).appendLine("views {")
        val inner = "$indent    "

        for (d in model.diagrams) {
            when (d) {
                is SystemContextDiagram -> {
                    // SystemContextDiagram hat kein eigenes system-Feld — wir suchen den
                    // ersten SoftwareSystem-Referenten in `elements`.
                    val systemId =
                        d.elements
                            .firstOrNull { id -> model.elements.any { it is C4SoftwareSystem && it.id == id } }
                    sb.append(inner).append("systemContext ")
                    if (systemId != null) sb.append(sanitizeId(systemId)).append(" ")
                    sb.append("\"").append(escape(d.id)).append("\"")
                    if (d.description != null) sb.append(" \"").append(escape(d.description!!)).append("\"")
                    sb.appendLine(" {")
                    sb.append(inner).appendLine("    include *")
                    sb.append(inner).appendLine("    autolayout lr")
                    sb.append(inner).appendLine("}")
                }
                is ContainerDiagram -> {
                    sb.append(inner).append("container ")
                    sb.append(sanitizeId(d.system)).append(" ")
                    sb.append("\"").append(escape(d.id)).append("\"")
                    if (d.description != null) sb.append(" \"").append(escape(d.description!!)).append("\"")
                    sb.appendLine(" {")
                    sb.append(inner).appendLine("    include *")
                    sb.append(inner).appendLine("    autolayout lr")
                    sb.append(inner).appendLine("}")
                }
                is ComponentDiagram -> {
                    sb.append(inner).append("component ")
                    sb.append(sanitizeId(d.container)).append(" ")
                    sb.append("\"").append(escape(d.id)).append("\"")
                    if (d.description != null) sb.append(" \"").append(escape(d.description!!)).append("\"")
                    sb.appendLine(" {")
                    sb.append(inner).appendLine("    include *")
                    sb.append(inner).appendLine("    autolayout lr")
                    sb.append(inner).appendLine("}")
                }
                is DeploymentDiagram -> {
                    sb.append(inner).append("deployment * ")
                    val env = (d.id).ifBlank { "Production" }
                    sb.append("\"").append(escape(env)).append("\" ")
                    sb.append("\"").append(escape(d.id)).append("\"")
                    if (d.description != null) sb.append(" \"").append(escape(d.description!!)).append("\"")
                    sb.appendLine(" {")
                    sb.append(inner).appendLine("    include *")
                    sb.append(inner).appendLine("    autolayout lr")
                    sb.append(inner).appendLine("}")
                }
                is SystemLandscapeDiagram -> {
                    sb.append(inner).append("systemLandscape ")
                    sb.append("\"").append(escape(d.id)).append("\"")
                    if (d.description != null) sb.append(" \"").append(escape(d.description!!)).append("\"")
                    sb.appendLine(" {")
                    sb.append(inner).appendLine("    include *")
                    sb.append(inner).appendLine("    autolayout lr")
                    sb.append(inner).appendLine("}")
                }
                else -> {
                    // DynamicDiagram, future variants: dropped with a comment for now (parser doesn't read them either).
                    sb
                        .append(inner)
                        .append("// Dynamic / unsupported view '")
                        .append(d.id)
                        .appendLine("' skipped on export (V1.1)")
                }
            }
        }

        // If there are no diagrams at all, emit nothing — empty views block is still valid Structurizr DSL
        sb.append(indent).appendLine("}")
        return sb.toString()
    }

    // ── identifier sanitisation ──────────────────────────────────────────────

    /**
     * Konvertiert eine kUML-ElementId in eine Structurizr-DSL-konforme Identifier.
     * Erlaubt sind nur ASCII-Buchstaben/Ziffern/Underscore — alles andere wird zu `_`.
     * Identifier, die mit einer Ziffer beginnen, bekommen einen führenden `e_`-Prefix.
     */
    private fun sanitizeId(raw: String): String {
        if (raw.isEmpty()) return "_"
        val out = StringBuilder()
        for (ch in raw) {
            out.append(if (ch.isLetterOrDigit() || ch == '_') ch else '_')
        }
        // Collapse runs of underscores
        val collapsed = out.toString().replace(Regex("_+"), "_").trim('_')
        if (collapsed.isEmpty()) return "_"
        return if (collapsed.first().isDigit()) "e_$collapsed" else collapsed
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
