package dev.kuml.c4.dsl.print

import dev.kuml.c4.model.C4CodeElement
import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4DeploymentNode
import dev.kuml.c4.model.C4Diagram
import dev.kuml.c4.model.C4Element
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.ComponentDiagram
import dev.kuml.c4.model.ContainerDiagram
import dev.kuml.c4.model.DeploymentDiagram
import dev.kuml.c4.model.DynamicDiagram
import dev.kuml.c4.model.ElementId
import dev.kuml.c4.model.SystemContextDiagram
import dev.kuml.c4.model.SystemLandscapeDiagram
import dev.kuml.core.dsl.layout.LayoutMetadataKeys
import dev.kuml.core.model.KumlMetaValue

/**
 * Pretty-prints a [C4Model] as a `*.kuml.kts` source string.
 *
 * Mirrors [dev.kuml.uml.dsl.print.UmlModelDslPrinter] but targets the C4 DSL
 * (`c4Model { … }`, see [dev.kuml.c4.dsl.C4Entrypoints]). The C4 builder
 * dialect is fundamentally different from the UML compiler dialect the UML
 * printer emits: C4 element-creating functions (`person`, `softwareSystem`,
 * `container`, `component`, `deploymentNode`, `node`) take **no `id`
 * parameter at all** — every id is auto-generated sequentially by
 * [dev.kuml.c4.dsl.C4Ids.generateId] — and relationships / diagram filters
 * (`relationship`, `include`, `exclude`, `system =`, `container =`) require
 * **object references**, not string ids (there is no `…ById` sibling family
 * as in the UML/SysML 2 dialects). Reprinted ids therefore will **not**
 * match the original model's ids — that is expected and harmless: nothing
 * in the C4 DSL lets a caller pin an id, so id-identity was never part of
 * this printer's round-trip contract. What round-trips is *structure*: the
 * same persons/systems/containers/components/deployment-nodes, the same
 * nesting, the same relationships (by element identity within the printed
 * script), and the same diagrams.
 *
 * ## Object-reference plumbing (the `lateinit var` pattern)
 *
 * Containers and components are only reachable as return values *inside*
 * their enclosing `softwareSystem { … }` / `container { … }` lambda — a
 * `val` declared inside that lambda is invisible to a later top-level
 * `relationship(...)` or diagram `include(...)` call. Whenever a nested
 * element is referenced by a relationship or a diagram, this printer
 * predeclares a `lateinit var` immediately before the enclosing top-level
 * statement and assigns it inside the nested block:
 *
 * ```kotlin
 * lateinit var c4v3: C4Container
 * val c4v1 = softwareSystem("Internet Banking System") {
 *     c4v3 = container("Web Application") { technology = "React" }
 *     container("API Application") { technology = "Spring Boot" }
 * }
 * relationship(c4v3, c4v7)
 * ```
 *
 * Unreferenced nested elements are printed as plain unassigned calls, so the
 * common case (no container/component-level relationships) stays as clean
 * as real hand-written scripts (see `internet-banking.kuml.kts`).
 *
 * `containerInstance(name, containerId)` — the one call in the DSL that
 * creates a [C4Container] — returns `Unit`, not the created container, so a
 * deployed container instance can **never** be referenced by a later
 * `relationship(...)` / diagram call. If the model does reference one (a
 * relationship or diagram touches a container-instance id), that reference
 * is printed as a `// TODO` comment instead of a dangling/incorrect call.
 * `containerId` itself *does* round-trip — as `containerId = <var>.id`, a
 * live Kotlin expression reading the original container's freshly
 * regenerated id at script-eval time (ids are not user-pinnable in the C4
 * DSL — see [dev.kuml.c4.dsl.C4Ids.generateId]) — as long as the original
 * container ([C4Container.instanceOf]) is itself printed; otherwise the
 * printer falls back to an empty placeholder and a `// TODO` comment.
 *
 * ## Known non-round-tripping cases (per ADR-0017, kept intentionally narrow)
 *
 * - [ContainerDiagram] / [ComponentDiagram] — the printer reconstructs these
 *   exactly whenever the diagram's stored `elements` genuinely exist in the
 *   model (via the builders' `show*`/`exclude(...)` flags plus
 *   [dev.kuml.c4.dsl.ContainerDiagramBuilder.include] /
 *   [dev.kuml.c4.dsl.ComponentDiagramBuilder.include] for whatever the flags
 *   can't reach on their own). The only residual gap is a truly dangling
 *   element id (one that does not resolve to any printed C4 element at all)
 *   or a relationship subset that the diagram's coarse `showRelationships`
 *   boolean cannot express — both still fall back to a `// TODO` naming the
 *   missing/extra ids.
 *
 * Format:
 * ```kotlin
 * c4Model(name = "Internet Banking System") {
 *     val c4v0 = person("Customer") { description = "A customer" }
 *     val c4v1 = softwareSystem("Internet Banking System") {
 *         container("Web Application") { technology = "React" }
 *     }
 *     relationship(c4v0, c4v1) { technology = "HTTPS" }
 *     systemContextDiagram("Context") { include(c4v0, c4v1) }
 * }
 * ```
 */
public object C4DslPrinter {
    public fun print(model: C4Model): String {
        val sb = StringBuilder()
        if (hasAnyPrintableLayoutHints(model.elements)) {
            sb.appendLine("import dev.kuml.core.dsl.layout.layout")
        }
        sb.appendLine("c4Model(name = ${quote(model.name)}${descArg(model.description)}) {")

        val elementById: Map<ElementId, C4Element> = model.elements.associateBy { it.id }
        val allDeploymentNodes = model.elements.filterIsInstance<C4DeploymentNode>()
        val childNodeIds = allDeploymentNodes.flatMap { it.children }.toSet()
        val topLevelIds =
            model.elements
                .filter { it is C4Person || it is C4SoftwareSystem || (it is C4DeploymentNode && it.id !in childNodeIds) }
                .map { it.id }
                .toSet()
        // Stable sort: Person/SoftwareSystem always printed before DeploymentNode, so a
        // containerInstance(...) call (which references an already-assigned Container
        // lateinit var, see printDeploymentNode) never runs before that var's assignment.
        // Within each group, the original model order is preserved (sortedBy is stable).
        val orderedTop =
            model.elements
                .filter { it.id in topLevelIds }
                .sortedBy { if (it is C4DeploymentNode) 1 else 0 }

        val referencedIds = computeReferencedIds(model)
        val ctx = Ctx()

        for (el in orderedTop) {
            when (el) {
                is C4Person -> {
                    val v = ctx.freshVar()
                    ctx.varNames[el.id] = v
                    ctx.printed += el.id
                    printPerson(sb = sb, varName = v, p = el)
                }
                is C4SoftwareSystem -> {
                    declareLateinitForSystem(sb = sb, s = el, elementById = elementById, referencedIds = referencedIds, ctx = ctx)
                    val v = ctx.freshVar()
                    ctx.varNames[el.id] = v
                    ctx.printed += el.id
                    printSoftwareSystem(sb = sb, varName = v, s = el, elementById = elementById, ctx = ctx)
                }
                is C4DeploymentNode -> {
                    declareLateinitForNode(sb = sb, n = el, elementById = elementById, referencedIds = referencedIds, ctx = ctx)
                    val v = ctx.freshVar()
                    ctx.varNames[el.id] = v
                    ctx.printed += el.id
                    printDeploymentNode(
                        sb = sb,
                        indent = "    ",
                        funcName = "deploymentNode",
                        varName = v,
                        // Top-level root node: never predeclared via a `lateinit var` (only its
                        // descendants can be — see declareLateinitForNode), so this must be a
                        // fresh `val` declaration, not a bare assignment to an undeclared name.
                        isFreshDeclaration = true,
                        n = el,
                        elementById = elementById,
                        ctx = ctx,
                    )
                }
                else -> Unit
            }
        }

        model.relationships.forEach { printRelationship(sb = sb, r = it, ctx = ctx) }
        model.diagrams.forEach { printDiagram(sb = sb, d = it, model = model, ctx = ctx, elementById = elementById) }

        model.elements.filterNot { it.id in ctx.printed }.forEach { el ->
            sb.appendLine(
                "    // TODO: ${el::class.simpleName} ${quote(el.name)} (id = ${quote(el.id)}) not serialized — " +
                    "element is not reachable from any top-level DSL entry point (orphaned container/component/code " +
                    "element, or a dangling parent reference).",
            )
        }

        sb.appendLine("}")
        return sb.toString()
    }

    // ── printer state ──────────────────────────────────────────────────────

    private class Ctx {
        val varNames: MutableMap<ElementId, String> = mutableMapOf()
        val printed: MutableSet<ElementId> = mutableSetOf()
        private var counter = 0

        fun freshVar(): String = "c4v${counter++}"
    }

    // ── referenced-id computation ─────────────────────────────────────────

    /**
     * Ids of every C4 element that must be reachable by variable somewhere in
     * the printed script: relationship endpoints, every id appearing in any
     * diagram's `elements`, plus (for Container/Component diagrams) the
     * containers/components that the diagram's `elements` implicitly
     * *excludes* relative to their parent's full container/component set —
     * those need a variable too, since `exclude(...)` also takes objects.
     */
    private fun computeReferencedIds(model: C4Model): Set<ElementId> {
        val ids = mutableSetOf<ElementId>()
        model.relationships.forEach {
            ids += it.source
            ids += it.target
        }
        // A containerInstance(...) call round-trips its containerId as `<var>.id` (see
        // printDeploymentNode), so the original container it points to must always have a
        // lateinit var predeclared for it, even if nothing else in the model references it.
        model.elements.filterIsInstance<C4Container>().forEach { c -> c.instanceOf?.let { ids += it } }
        val allContainers = model.elements.filterIsInstance<C4Container>()
        val allComponents = model.elements.filterIsInstance<C4Component>()
        model.diagrams.forEach { d ->
            ids += d.elements
            when (d) {
                is ContainerDiagram -> {
                    ids += d.system
                    val systemContainers = allContainers.filter { it.system == d.system }.map { it.id }
                    ids += (systemContainers.toSet() - d.elements.toSet())
                }
                is ComponentDiagram -> {
                    ids += d.container
                    val containerComponents = allComponents.filter { it.container == d.container }.map { it.id }
                    ids += (containerComponents.toSet() - d.elements.toSet())
                }
                else -> Unit
            }
        }
        return ids
    }

    // ── lateinit-var predeclaration ─────────────────────────────────────────

    private fun declareLateinitForSystem(
        sb: StringBuilder,
        s: C4SoftwareSystem,
        elementById: Map<ElementId, C4Element>,
        referencedIds: Set<ElementId>,
        ctx: Ctx,
    ) {
        val containers = s.containers.mapNotNull { elementById[it] as? C4Container }
        for (c in containers) {
            if (c.id in referencedIds) {
                val v = ctx.freshVar()
                ctx.varNames[c.id] = v
                sb.appendLine("    lateinit var $v: C4Container")
            }
            val comps = c.components.mapNotNull { elementById[it] as? C4Component }
            for (comp in comps) {
                if (comp.id in referencedIds) {
                    val v = ctx.freshVar()
                    ctx.varNames[comp.id] = v
                    sb.appendLine("    lateinit var $v: C4Component")
                }
                val codeElements = comp.codeElements.mapNotNull { elementById[it] as? C4CodeElement }
                for (ce in codeElements) {
                    if (ce.id in referencedIds) {
                        val v = ctx.freshVar()
                        ctx.varNames[ce.id] = v
                        sb.appendLine("    lateinit var $v: C4CodeElement")
                    }
                }
            }
        }
    }

    /**
     * Predeclares `lateinit var`s for referenced descendants of a deployment
     * node: nested child nodes (recursively). Container instances are
     * deliberately **not** handled here — `containerInstance(...)` returns
     * `Unit`, so no variable can ever be bound to one (see class KDoc).
     */
    private fun declareLateinitForNode(
        sb: StringBuilder,
        n: C4DeploymentNode,
        elementById: Map<ElementId, C4Element>,
        referencedIds: Set<ElementId>,
        ctx: Ctx,
    ) {
        val children = n.children.mapNotNull { elementById[it] as? C4DeploymentNode }
        for (child in children) {
            if (child.id in referencedIds) {
                val v = ctx.freshVar()
                ctx.varNames[child.id] = v
                sb.appendLine("    lateinit var $v: C4DeploymentNode")
            }
            declareLateinitForNode(sb = sb, n = child, elementById = elementById, referencedIds = referencedIds, ctx = ctx)
        }
    }

    // ── element printing ─────────────────────────────────────────────────

    private fun printPerson(
        sb: StringBuilder,
        varName: String,
        p: C4Person,
    ) {
        val inner = StringBuilder()
        p.description?.let { inner.appendLine("        description = ${quote(it)}") }
        if (p.external) inner.appendLine("        external = true")
        p.location?.let { inner.appendLine("        location = ${quote(it)}") }
        printLayoutHints(sb = inner, metadata = p.metadata, indent = "        ")
        if (inner.isBlank()) {
            sb.appendLine("    val $varName = person(${quote(p.name)})")
        } else {
            sb.appendLine("    val $varName = person(${quote(p.name)}) {")
            sb.append(inner)
            sb.appendLine("    }")
        }
    }

    private fun printSoftwareSystem(
        sb: StringBuilder,
        varName: String,
        s: C4SoftwareSystem,
        elementById: Map<ElementId, C4Element>,
        ctx: Ctx,
    ) {
        sb.appendLine("    val $varName = softwareSystem(${quote(s.name)}) {")
        s.description?.let { sb.appendLine("        description = ${quote(it)}") }
        if (s.external) sb.appendLine("        external = true")
        s.location?.let { sb.appendLine("        location = ${quote(it)}") }
        val containers = s.containers.mapNotNull { elementById[it] as? C4Container }
        for (c in containers) printContainer(sb = sb, indent = "        ", c = c, elementById = elementById, ctx = ctx)
        printLayoutHints(sb = sb, metadata = s.metadata, indent = "        ")
        sb.appendLine("    }")
    }

    private fun printContainer(
        sb: StringBuilder,
        indent: String,
        c: C4Container,
        elementById: Map<ElementId, C4Element>,
        ctx: Ctx,
    ) {
        ctx.printed += c.id
        val assignedVar = ctx.varNames[c.id]
        val prefix = if (assignedVar != null) "$indent$assignedVar = " else indent
        val comps = c.components.mapNotNull { elementById[it] as? C4Component }
        val inner = StringBuilder()
        c.description?.let { inner.appendLine("$indent    description = ${quote(it)}") }
        c.technology?.let { inner.appendLine("$indent    technology = ${quote(it)}") }
        for (comp in comps) printComponent(sb = inner, indent = "$indent    ", c = comp, elementById = elementById, ctx = ctx)
        printLayoutHints(sb = inner, metadata = c.metadata, indent = "$indent    ")
        if (inner.isBlank()) {
            sb.appendLine("${prefix}container(${quote(c.name)})")
        } else {
            sb.appendLine("${prefix}container(${quote(c.name)}) {")
            sb.append(inner)
            sb.appendLine("$indent}")
        }
    }

    private fun printComponent(
        sb: StringBuilder,
        indent: String,
        c: C4Component,
        elementById: Map<ElementId, C4Element>,
        ctx: Ctx,
    ) {
        ctx.printed += c.id
        val assignedVar = ctx.varNames[c.id]
        val prefix = if (assignedVar != null) "$indent$assignedVar = " else indent
        val codeElements = c.codeElements.mapNotNull { elementById[it] as? C4CodeElement }
        val inner = StringBuilder()
        c.description?.let { inner.appendLine("$indent    description = ${quote(it)}") }
        c.technology?.let { inner.appendLine("$indent    technology = ${quote(it)}") }
        for (ce in codeElements) printCodeElement(sb = inner, indent = "$indent    ", ce = ce, ctx = ctx)
        printLayoutHints(sb = inner, metadata = c.metadata, indent = "$indent    ")
        if (inner.isBlank()) {
            sb.appendLine("${prefix}component(${quote(c.name)})")
        } else {
            sb.appendLine("${prefix}component(${quote(c.name)}) {")
            sb.append(inner)
            sb.appendLine("$indent}")
        }
    }

    private fun printCodeElement(
        sb: StringBuilder,
        indent: String,
        ce: C4CodeElement,
        ctx: Ctx,
    ) {
        ctx.printed += ce.id
        val assignedVar = ctx.varNames[ce.id]
        val prefix = if (assignedVar != null) "$indent$assignedVar = " else indent
        val inner = StringBuilder()
        ce.description?.let { inner.appendLine("$indent    description = ${quote(it)}") }
        ce.technology?.let { inner.appendLine("$indent    technology = ${quote(it)}") }
        printLayoutHints(sb = inner, metadata = ce.metadata, indent = "$indent    ")
        if (inner.isBlank()) {
            sb.appendLine("${prefix}codeElement(${quote(ce.name)})")
        } else {
            sb.appendLine("${prefix}codeElement(${quote(ce.name)}) {")
            sb.append(inner)
            sb.appendLine("$indent}")
        }
    }

    private fun printDeploymentNode(
        sb: StringBuilder,
        indent: String,
        funcName: String,
        varName: String?,
        n: C4DeploymentNode,
        elementById: Map<ElementId, C4Element>,
        ctx: Ctx,
        isFreshDeclaration: Boolean = false,
    ) {
        ctx.printed += n.id
        val prefix =
            when {
                varName == null -> indent
                // Top-level root node: freshly assigned in print()'s main loop, never predeclared
                // via a `lateinit var` — needs its own `val` here, unlike the nested case below.
                isFreshDeclaration -> "${indent}val $varName = "
                // Nested child node: varName is non-null only when declareLateinitForNode
                // already emitted `lateinit var $varName: C4DeploymentNode` for it — plain
                // assignment, no `val`.
                else -> "$indent$varName = "
            }
        val inner = StringBuilder()
        n.description?.let { inner.appendLine("$indent    description = ${quote(it)}") }
        n.technology?.let { inner.appendLine("$indent    technology = ${quote(it)}") }
        if (n.instances != 1) inner.appendLine("$indent    instances = ${n.instances}")
        val children = n.children.mapNotNull { elementById[it] as? C4DeploymentNode }
        for (child in children) {
            val childVar = ctx.varNames[child.id]
            printDeploymentNode(
                sb = inner,
                indent = "$indent    ",
                funcName = "node",
                varName = childVar,
                n = child,
                elementById = elementById,
                ctx = ctx,
            )
        }
        val instances = n.containerInstances.mapNotNull { elementById[it] as? C4Container }
        for (ci in instances) {
            ctx.printed += ci.id
            // containerInstance(...) returns Unit — can never be assigned to a var, see class KDoc.
            // The referenced container's id is round-tripped as `<var>.id` — a live Kotlin
            // expression reading the freshly regenerated id at script-eval time — rather than a
            // literal, since C4 ids are not user-pinnable (see C4Ids.generateId()).
            val instanceOf: ElementId? = ci.instanceOf
            val originalVar = instanceOf?.let { ctx.varNames[it] }
            if (originalVar != null) {
                inner.appendLine("$indent    containerInstance(${quote(ci.name)}, containerId = $originalVar.id)")
            } else {
                val reason =
                    if (instanceOf == null) {
                        "C4Container.instanceOf is null (no source container recorded for this instance)."
                    } else {
                        "container id '${sanitizeForComment(instanceOf)}' does not resolve to a printed C4 element."
                    }
                inner.appendLine(
                    "$indent    // TODO: containerInstance ${quote(ci.name)} (id = ${quote(ci.id)}) not serialized with its " +
                        "original containerId — $reason",
                )
                inner.appendLine("$indent    containerInstance(${quote(ci.name)}, containerId = \"\")")
            }
        }
        printLayoutHints(sb = inner, metadata = n.metadata, indent = "$indent    ")
        if (inner.isBlank()) {
            sb.appendLine("$prefix$funcName(${quote(n.name)})")
        } else {
            sb.appendLine("$prefix$funcName(${quote(n.name)}) {")
            sb.append(inner)
            sb.appendLine("$indent}")
        }
    }

    // ── relationships ──────────────────────────────────────────────────────

    private fun printRelationship(
        sb: StringBuilder,
        r: C4Relationship,
        ctx: Ctx,
    ) {
        val srcVar = ctx.varNames[r.source]
        val tgtVar = ctx.varNames[r.target]
        if (srcVar == null || tgtVar == null) {
            sb.appendLine(
                "    // TODO: C4Relationship ${quote(r.id)} (label = ${quote(r.label)}) not serialized — " +
                    "endpoint '${sanitizeForComment(if (srcVar == null) r.source else r.target)}' does not resolve to a " +
                    "printed C4 element (likely a container-instance id, which containerInstance(...) never returns a " +
                    "reference for).",
            )
            return
        }
        val hasBody = r.description != null || r.technology != null || r.bidirectional
        if (!hasBody) {
            sb.appendLine("    relationship($srcVar, $tgtVar)")
        } else {
            sb.appendLine("    relationship($srcVar, $tgtVar) {")
            r.description?.let { sb.appendLine("        description = ${quote(it)}") }
            r.technology?.let { sb.appendLine("        technology = ${quote(it)}") }
            if (r.bidirectional) sb.appendLine("        bidirectional = true")
            sb.appendLine("    }")
        }
    }

    // ── diagrams ───────────────────────────────────────────────────────────

    private fun printDiagram(
        sb: StringBuilder,
        d: C4Diagram,
        model: C4Model,
        ctx: Ctx,
        elementById: Map<ElementId, C4Element>,
    ) {
        when (d) {
            is SystemContextDiagram -> printSystemContextDiagram(sb = sb, d = d, ctx = ctx)
            is SystemLandscapeDiagram -> printSystemLandscapeDiagram(sb = sb, d = d, model = model, ctx = ctx)
            is ContainerDiagram -> printContainerDiagram(sb = sb, d = d, model = model, ctx = ctx)
            is ComponentDiagram -> printComponentDiagram(sb = sb, d = d, model = model, ctx = ctx)
            is DeploymentDiagram -> printDeploymentDiagram(sb = sb, d = d, model = model, ctx = ctx)
            is DynamicDiagram -> printDynamicDiagram(sb = sb, d = d, ctx = ctx)
        }
    }

    private fun descArg(description: String?): String = if (description != null) ", description = ${quote(description)}" else ""

    private fun printSystemContextDiagram(
        sb: StringBuilder,
        d: SystemContextDiagram,
        ctx: Ctx,
    ) {
        val varsOrNull = d.elements.map { ctx.varNames[it] }
        sb.appendLine("    systemContextDiagram(${quote(d.name)}${descArg(d.description)}) {")
        if (varsOrNull.any { it == null }) {
            val missing = d.elements.filterIndexed { i, _ -> varsOrNull[i] == null }
            sb.appendLine(
                "        // TODO: SystemContextDiagram ${quote(d.name)} not fully serialized — " +
                    "element id(s) ${sanitizeForComment(missing)} do not resolve to a printed C4 element.",
            )
        }
        val vars = varsOrNull.filterNotNull()
        if (vars.isNotEmpty()) sb.appendLine("        include(${vars.joinToString(", ")})")
        sb.appendLine("    }")
    }

    private fun printSystemLandscapeDiagram(
        sb: StringBuilder,
        d: SystemLandscapeDiagram,
        model: C4Model,
        ctx: Ctx,
    ) {
        val allPersonsAndSystems =
            model.elements
                .filter { it is C4Person || it is C4SoftwareSystem }
                .map { it.id }
                .toSet()
        val actual = d.elements.toSet()
        sb.appendLine("    systemLandscapeDiagram(${quote(d.name)}${descArg(d.description)}) {")
        if (actual != allPersonsAndSystems) {
            val varsOrNull = d.elements.map { ctx.varNames[it] }
            if (varsOrNull.any { it == null }) {
                val missing = d.elements.filterIndexed { i, _ -> varsOrNull[i] == null }
                sb.appendLine(
                    "        // TODO: SystemLandscapeDiagram ${quote(d.name)} subset could not be fully resolved to " +
                        "printed vars; missing id(s): ${sanitizeForComment(missing)}.",
                )
            } else {
                sb.appendLine("        includeAllSystems = false")
                sb.appendLine("        includeAllPersons = false")
                val vars = varsOrNull.filterNotNull()
                if (vars.isNotEmpty()) sb.appendLine("        include(${vars.joinToString(", ")})")
            }
        }
        sb.appendLine("    }")
    }

    // ── ContainerDiagram / ComponentDiagram best-effort reconstruction ─────

    private fun findExternalSystemsFor(
        model: C4Model,
        systemId: ElementId,
        systemContainers: List<ElementId>,
    ): Set<ElementId> {
        val relatedIds = mutableSetOf<ElementId>()
        for (rel in model.relationships) {
            val fromThisSystem = (rel.source == systemId) || (rel.source in systemContainers)
            val targetIsSoftwareSystem = model.elements.find { it.id == rel.target } is C4SoftwareSystem
            if (fromThisSystem && targetIsSoftwareSystem && rel.target != systemId) relatedIds.add(rel.target)
            val sourceIsSoftwareSystem = model.elements.find { it.id == rel.source } is C4SoftwareSystem
            val toThisSystem = (rel.target == systemId) || (rel.target in systemContainers)
            if (sourceIsSoftwareSystem && rel.source != systemId && toThisSystem) relatedIds.add(rel.source)
        }
        return relatedIds
    }

    private fun findRelatedPersonsFor(
        model: C4Model,
        systemId: ElementId,
        systemContainers: List<ElementId>,
    ): Set<ElementId> {
        val relatedIds = mutableSetOf<ElementId>()
        val boundary = systemContainers.toSet() + systemId
        for (rel in model.relationships) {
            val sourceEl = model.elements.find { it.id == rel.source }
            val targetEl = model.elements.find { it.id == rel.target }
            if (sourceEl is C4Person && rel.target in boundary) relatedIds.add(rel.source)
            if (targetEl is C4Person && rel.source in boundary) relatedIds.add(rel.target)
        }
        return relatedIds
    }

    /**
     * Decides a diagram builder boolean flag's printed value plus any elements that must be
     * added on top of it via `include(...)`, given the elements the original diagram actually
     * contains ([target]) versus what the flag's auto-reconstruction logic would find on its
     * own ([auto]):
     *
     * - `target == auto` → the flag can stay at its default `true`; nothing else needed. This
     *   is the common case that mirrors hand-written scripts (no `include(...)` noise).
     * - `target` is empty (and thus different from a non-empty [auto]) → the flag must be
     *   turned off (`false`) to suppress [auto]'s elements; no `include(...)` needed since
     *   nothing is meant to be shown.
     * - otherwise → the flag is turned off and the *entire* [target] set is re-added via
     *   `include(...)`, since turning the flag off also suppresses whatever overlap [auto] had
     *   with [target].
     *
     * In every branch the returned pair's "effective" element set (flag-contributed elements
     * when `true`, else the returned extra set) equals [target] exactly — see call sites.
     */
    private fun reconcile(
        target: Set<ElementId>,
        auto: Set<ElementId>,
    ): Pair<Boolean, Set<ElementId>> =
        when {
            target == auto -> true to emptySet()
            target.isEmpty() -> false to emptySet()
            else -> false to target
        }

    /**
     * Emits an `include(...)` call for [extraIds] (elements a coarse show*-flag/exclude() flag
     * cannot reach on its own) if every id resolves to a printed variable, or a `// TODO`
     * naming the ids that don't (a genuinely dangling/non-existent element reference) — omitted
     * entirely rather than partially, mirroring the existing `exclude(...)` fallback.
     *
     * @return [extraIds] unchanged if the `include(...)` call was emitted, or an empty set if a
     *   `// TODO` was emitted instead — callers use this to compute the *actually reconstructed*
     *   element set for the final `matches` check, so a genuinely dangling id correctly fails
     *   that check instead of being silently counted as "included".
     */
    private fun printInclude(
        sb: StringBuilder,
        diagramName: String,
        extraIds: Set<ElementId>,
        ctx: Ctx,
    ): Set<ElementId> {
        if (extraIds.isEmpty()) return emptySet()
        val extraVars = extraIds.mapNotNull { ctx.varNames[it] }
        return if (extraVars.size == extraIds.size) {
            sb.appendLine("        include(${extraVars.joinToString(", ")})")
            extraIds
        } else {
            val unresolved = extraIds.filter { ctx.varNames[it] == null }
            sb.appendLine(
                "        // TODO: ${unresolved.size} element(s) of ${quote(diagramName)} could not be referenced by " +
                    "variable for include() — ids: ${sanitizeForComment(unresolved)}.",
            )
            emptySet()
        }
    }

    private fun printContainerDiagram(
        sb: StringBuilder,
        d: ContainerDiagram,
        model: C4Model,
        ctx: Ctx,
    ) {
        val systemVar = ctx.varNames[d.system]
        sb.appendLine("    containerDiagram(${quote(d.name)}${descArg(d.description)}) {")
        if (systemVar == null) {
            sb.appendLine(
                "        // TODO: ContainerDiagram ${quote(d.name)} not serialized — system id '${sanitizeForComment(d.system)}' " +
                    "does not resolve to a printed C4SoftwareSystem.",
            )
            sb.appendLine("    }")
            return
        }

        val allSystemContainers =
            model.elements
                .filterIsInstance<C4Container>()
                .filter { it.system == d.system }
                .map { it.id }
        val diagElements = d.elements.toSet()
        val excluded = allSystemContainers.toSet() - diagElements
        val includedContainers = allSystemContainers.filter { it !in excluded }

        val externalPresent = diagElements - includedContainers.toSet() - setOf(d.system)
        val diagExternalSystems = externalPresent.filter { id -> model.elements.find { it.id == id } is C4SoftwareSystem }.toSet()
        val diagPersons = externalPresent.filter { id -> model.elements.find { it.id == id } is C4Person }.toSet()

        val autoExternalSystems = findExternalSystemsFor(model = model, systemId = d.system, systemContainers = includedContainers)
        val autoPersons = findRelatedPersonsFor(model = model, systemId = d.system, systemContainers = includedContainers)

        val (showExternalSystems, extraExternal) = reconcile(target = diagExternalSystems, auto = autoExternalSystems)
        val (showRelatedPersons, extraPersons) = reconcile(target = diagPersons, auto = autoPersons)

        // Used only to decide showRelationships/finalRelationships below: whether a dangling
        // extra id is nominally present here has no effect on that filter, since a relationship
        // endpoint always refers to a real element and can never equal a non-existent id.
        val allElementsForRelFilter = setOf(d.system) + includedContainers + extraExternal + extraPersons
        val relsIfOn =
            model.relationships
                .filter { it.source in allElementsForRelFilter && it.target in allElementsForRelFilter }
                .map { it.id }
                .toSet()
        val showRelationships = !(d.relationships.isEmpty() && relsIfOn.isNotEmpty())
        val finalRelationships = if (showRelationships) relsIfOn else emptySet()

        sb.appendLine("        system = $systemVar")
        if (!showExternalSystems) sb.appendLine("        showExternalSystems = false")
        if (!showRelatedPersons) sb.appendLine("        showRelatedPersons = false")
        if (!showRelationships) sb.appendLine("        showRelationships = false")
        if (excluded.isNotEmpty()) {
            val excludedVars = excluded.mapNotNull { ctx.varNames[it] }
            if (excludedVars.size == excluded.size) {
                sb.appendLine("        exclude(${excludedVars.joinToString(", ")})")
            } else {
                sb.appendLine(
                    "        // TODO: ${excluded.size} excluded container(s) of ${quote(d.name)} could not all be " +
                        "referenced by variable — exclude() call omitted, reconstructed diagram may show extra containers.",
                )
            }
        }
        val printedExtra = printInclude(sb = sb, diagramName = d.name, extraIds = extraExternal + extraPersons, ctx = ctx)
        // The final, *actually reconstructed* element set — unlike allElementsForRelFilter above,
        // this only counts extraExternal/extraPersons if printInclude actually emitted them.
        val effectiveExternal = if (showExternalSystems) autoExternalSystems else printedExtra.intersect(extraExternal)
        val effectivePersons = if (showRelatedPersons) autoPersons else printedExtra.intersect(extraPersons)
        val allElements = setOf(d.system) + includedContainers + effectiveExternal + effectivePersons
        val matches = allElements == diagElements && finalRelationships == d.relationships.toSet()
        if (!matches) {
            sb.appendLine(
                "        // TODO: ContainerDiagram ${quote(d.name)} could not be reconstructed exactly. " +
                    "Missing: ${sanitizeForComment(diagElements - allElements)}. " +
                    "Extra: ${sanitizeForComment(allElements - diagElements)}.",
            )
        }
        sb.appendLine("    }")
    }

    private fun findExternalContainersFor(
        model: C4Model,
        containerId: ElementId,
        containerComponents: List<ElementId>,
    ): Set<ElementId> {
        val relatedIds = mutableSetOf<ElementId>()
        val allContainers = model.elements.filterIsInstance<C4Container>()
        for (rel in model.relationships) {
            val fromThisContainer = (rel.source == containerId) || (rel.source in containerComponents)
            val targetIsContainer = allContainers.any { it.id == rel.target }
            if (fromThisContainer && targetIsContainer && rel.target != containerId) relatedIds.add(rel.target)
            val sourceIsContainer = allContainers.any { it.id == rel.source }
            val toThisContainer = (rel.target == containerId) || (rel.target in containerComponents)
            if (sourceIsContainer && rel.source != containerId && toThisContainer) relatedIds.add(rel.source)
        }
        return relatedIds
    }

    private fun printComponentDiagram(
        sb: StringBuilder,
        d: ComponentDiagram,
        model: C4Model,
        ctx: Ctx,
    ) {
        val containerVar = ctx.varNames[d.container]
        sb.appendLine("    componentDiagram(${quote(d.name)}${descArg(d.description)}) {")
        if (containerVar == null) {
            sb.appendLine(
                "        // TODO: ComponentDiagram ${quote(d.name)} not serialized — container id '${sanitizeForComment(d.container)}' " +
                    "does not resolve to a printed C4Container.",
            )
            sb.appendLine("    }")
            return
        }

        val allContainerComponents =
            model.elements
                .filterIsInstance<C4Component>()
                .filter { it.container == d.container }
                .map { it.id }
        val diagElements = d.elements.toSet()
        val excluded = allContainerComponents.toSet() - diagElements
        val includedComponents = allContainerComponents.filter { it !in excluded }

        val externalPresent = diagElements - includedComponents.toSet() - setOf(d.container)
        val autoExternal = findExternalContainersFor(model = model, containerId = d.container, containerComponents = includedComponents)
        val (showExternalReferences, extraExternal) = reconcile(target = externalPresent, auto = autoExternal)

        // Used only to decide showRelationships/finalRelationships below: whether a dangling
        // extra id is nominally present here has no effect on that filter, since a relationship
        // endpoint always refers to a real element and can never equal a non-existent id.
        val allElementsForRelFilter = setOf(d.container) + includedComponents + extraExternal
        val relsIfOn =
            model.relationships
                .filter { it.source in allElementsForRelFilter && it.target in allElementsForRelFilter }
                .map { it.id }
                .toSet()
        val showRelationships = !(d.relationships.isEmpty() && relsIfOn.isNotEmpty())
        val finalRelationships = if (showRelationships) relsIfOn else emptySet()

        sb.appendLine("        container = $containerVar")
        if (!showExternalReferences) sb.appendLine("        showExternalReferences = false")
        if (!showRelationships) sb.appendLine("        showRelationships = false")
        if (excluded.isNotEmpty()) {
            val excludedVars = excluded.mapNotNull { ctx.varNames[it] }
            if (excludedVars.size == excluded.size) {
                sb.appendLine("        exclude(${excludedVars.joinToString(", ")})")
            } else {
                sb.appendLine(
                    "        // TODO: ${excluded.size} excluded component(s) of ${quote(d.name)} could not all be " +
                        "referenced by variable — exclude() call omitted, reconstructed diagram may show extra components.",
                )
            }
        }
        val printedExtra = printInclude(sb = sb, diagramName = d.name, extraIds = extraExternal, ctx = ctx)
        // The final, *actually reconstructed* element set — unlike allElementsForRelFilter above,
        // this only counts extraExternal if printInclude actually emitted it.
        val effectiveExternal = if (showExternalReferences) autoExternal else printedExtra.intersect(extraExternal)
        val allElements = setOf(d.container) + includedComponents + effectiveExternal
        val matches = allElements == diagElements && finalRelationships == d.relationships.toSet()
        if (!matches) {
            sb.appendLine(
                "        // TODO: ComponentDiagram ${quote(d.name)} could not be reconstructed exactly. " +
                    "Missing: ${sanitizeForComment(diagElements - allElements)}. " +
                    "Extra: ${sanitizeForComment(allElements - diagElements)}.",
            )
        }
        sb.appendLine("    }")
    }

    private fun printDeploymentDiagram(
        sb: StringBuilder,
        d: DeploymentDiagram,
        model: C4Model,
        ctx: Ctx,
    ) {
        val allNodes = model.elements.filterIsInstance<C4DeploymentNode>()
        val childIds = allNodes.flatMap { it.children }.toSet()
        val defaultRoots = allNodes.filter { it.id !in childIds }.map { it.id }.toSet()
        val diagElements = d.elements.toSet()
        val parentOf = mutableMapOf<ElementId, ElementId>()
        for (n in allNodes) for (c in n.children) parentOf[c] = n.id
        val nodeIds = allNodes.map { it.id }.toSet()
        val includedNodeIds = diagElements.filter { it in nodeIds }
        val candidateRoots =
            includedNodeIds
                .filter { id ->
                    val p = parentOf[id]
                    p == null || p !in diagElements
                }.toSet()

        fun collect(nodeId: ElementId): Set<ElementId> {
            val node = allNodes.find { it.id == nodeId } ?: return emptySet()
            return setOf(nodeId) + node.children.flatMap { collect(it) }
        }
        val computedNodeIds = candidateRoots.flatMap { collect(it) }.toSet()
        val computedContainerInstanceIds = allNodes.filter { it.id in computedNodeIds }.flatMap { it.containerInstances }.toSet()
        val computedElements = computedNodeIds + computedContainerInstanceIds
        val computedRelationships =
            model.relationships
                .filter { it.source in computedElements && it.target in computedElements }
                .map { it.id }
                .toSet()

        sb.appendLine("    deploymentDiagram(${quote(d.name)}${descArg(d.description)}) {")
        if (candidateRoots != defaultRoots) {
            val rootVars = candidateRoots.mapNotNull { ctx.varNames[it] }
            if (rootVars.size == candidateRoots.size && rootVars.isNotEmpty()) {
                sb.appendLine("        include(${rootVars.joinToString(", ")})")
            } else if (candidateRoots.isNotEmpty()) {
                sb.appendLine(
                    "        // TODO: could not reference all root deployment nodes of ${quote(d.name)} by variable " +
                        "for include(); some root selection may be lost.",
                )
            }
        }
        val matches = computedElements == diagElements && computedRelationships == d.relationships.toSet()
        if (!matches) {
            sb.appendLine(
                "        // TODO: DeploymentDiagram ${quote(d.name)} could not be reconstructed exactly. " +
                    "Missing: ${sanitizeForComment(diagElements - computedElements)}. " +
                    "Extra: ${sanitizeForComment(computedElements - diagElements)}.",
            )
        }
        sb.appendLine("    }")
    }

    private fun printDynamicDiagram(
        sb: StringBuilder,
        d: DynamicDiagram,
        ctx: Ctx,
    ) {
        sb.appendLine("    dynamicDiagram(${quote(d.name)}${descArg(d.description)}) {")
        for (interaction in d.interactions.sortedBy { it.sequence }) {
            val fromVar = ctx.varNames[interaction.source]
            val toVar = ctx.varNames[interaction.target]
            if (fromVar == null || toVar == null) {
                sb.appendLine(
                    "        // TODO: interaction ${quote(interaction.id)} (${quote(interaction.description)}) not " +
                        "serialized — endpoint not resolvable to a printed element.",
                )
                continue
            }
            val fn = if (interaction.response) "response" else "interaction"
            val techArg = interaction.technology?.let { ", technology = ${quote(it)}" } ?: ""
            sb.appendLine("        $fn(${quote(interaction.description)}, from = $fromVar, to = $toVar$techArg)")
        }
        sb.appendLine("    }")
    }

    // ── layout hints (grid drag-and-drop round-trip) ────────────────────────

    private fun printLayoutHints(
        sb: StringBuilder,
        metadata: Map<String, KumlMetaValue>,
        indent: String,
    ) {
        val col = metadata.intValue(LayoutMetadataKeys.GRID_COL)
        val row = metadata.intValue(LayoutMetadataKeys.GRID_ROW)
        val colSpan = metadata.intValue(LayoutMetadataKeys.GRID_COL_SPAN)
        val rowSpan = metadata.intValue(LayoutMetadataKeys.GRID_ROW_SPAN)
        val pinned = (metadata[LayoutMetadataKeys.PINNED] as? KumlMetaValue.Flag)?.value ?: false
        if (col == null && row == null && colSpan == null && rowSpan == null && !pinned) return
        sb.appendLine("$indent layout {")
        col?.let { sb.appendLine("$indent    col = $it") }
        row?.let { sb.appendLine("$indent    row = $it") }
        colSpan?.let { sb.appendLine("$indent    colSpan = $it") }
        rowSpan?.let { sb.appendLine("$indent    rowSpan = $it") }
        if (pinned) sb.appendLine("$indent    pinned = true")
        sb.appendLine("$indent }")
    }

    private fun hasLayoutHints(metadata: Map<String, KumlMetaValue>): Boolean =
        metadata.containsKey(LayoutMetadataKeys.GRID_COL) ||
            metadata.containsKey(LayoutMetadataKeys.GRID_ROW) ||
            metadata.containsKey(LayoutMetadataKeys.GRID_COL_SPAN) ||
            metadata.containsKey(LayoutMetadataKeys.GRID_ROW_SPAN) ||
            metadata.containsKey(LayoutMetadataKeys.PINNED)

    // All element kinds whose scope exposes a `layout { … }` DSL entry point are considered
    // here — DeploymentNodeScope now extends LayoutHintsScope too (see printDeploymentNode),
    // so C4DeploymentNode.metadata is included alongside the others.
    private fun hasAnyPrintableLayoutHints(elements: List<C4Element>): Boolean =
        elements.any { el ->
            val meta =
                when (el) {
                    is C4Person -> el.metadata
                    is C4SoftwareSystem -> el.metadata
                    is C4Container -> el.metadata
                    is C4Component -> el.metadata
                    is C4CodeElement -> el.metadata
                    is C4DeploymentNode -> el.metadata
                    else -> emptyMap()
                }
            hasLayoutHints(meta)
        }

    private fun Map<String, KumlMetaValue>.intValue(key: String): Int? = (this[key] as? KumlMetaValue.Integer)?.value?.toInt()

    // ── helpers ────────────────────────────────────────────────────────────

    private fun quote(s: String): String =
        buildString {
            append('"')
            for (ch in s) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }

    /**
     * Sanitizes a raw, unquoted text fragment (e.g. an [ElementId]) for safe embedding in a
     * single-line Kotlin `//` comment emitted as diagnostic output (`// TODO: …` / `// NOTE: …`).
     *
     * Unlike [quote], the fragments this is applied to are printed *outside* any string literal —
     * directly as bare text after `//`. [ElementId] is an unvalidated `String` (see
     * `C4Element.id` in `C4Element.kt`), so it may contain line-terminating characters. Without
     * sanitization, an id containing `\n` (or `\r`, or a Unicode line separator) followed by
     * Kotlin code would end the comment early and cause the remainder to be printed as live,
     * uncommented code in the generated `*.kuml.kts` script — a comment-injection vulnerability
     * that both hides malicious code from a human reviewer and could be picked up by an
     * automated compile step. Every character that any tool could plausibly treat as a line
     * break is replaced with a visible, non-breaking escape sequence.
     */
    private fun sanitizeForComment(s: String): String =
        buildString {
            for (ch in s) {
                when (ch.code) {
                    '\n'.code -> append("\\n")
                    '\r'.code -> append("\\r")
                    0x0085 -> append("\\u0085") // NEL
                    0x2028 -> append("\\u2028") // LINE SEPARATOR
                    0x2029 -> append("\\u2029") // PARAGRAPH SEPARATOR
                    else -> append(ch)
                }
            }
        }

    /** [sanitizeForComment] applied element-wise to a collection, rendered like [Collection.toString]. */
    private fun sanitizeForComment(ids: Collection<String>): String =
        ids.joinToString(prefix = "[", postfix = "]") { sanitizeForComment(it) }
}
