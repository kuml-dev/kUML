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
 *
 * ## Known non-round-tripping fields
 *
 * - [C4Model.description] — `c4Model(name, description, block)` accepts a
 *   `description` parameter but [dev.kuml.c4.dsl.C4ModelBuilder] never reads
 *   it; there is no way to set [C4Model.description] via the DSL at all.
 * - `containerInstance(name, containerId)`'s `containerId` parameter is
 *   never stored by [dev.kuml.c4.dsl.DeploymentNodeScopeImpl] — the printer
 *   emits an empty placeholder and a trailing comment.
 * - [C4CodeElement] — [dev.kuml.c4.dsl.C4ModelBuilder] has no `codeElement()`
 *   entry point at any level; every code element becomes a `// TODO`.
 * - [C4DeploymentNode.metadata] layout hints (`GRID_COL`/`GRID_ROW`/`PINNED`/
 *   etc.) — unlike [dev.kuml.c4.dsl.PersonScope], [dev.kuml.c4.dsl.SoftwareSystemScope],
 *   [dev.kuml.c4.dsl.ContainerScope] and [dev.kuml.c4.dsl.ComponentScope],
 *   [dev.kuml.c4.dsl.DeploymentNodeScope] does not extend
 *   [dev.kuml.core.dsl.layout.LayoutHintsScope], so no `layout { … }` call is
 *   available inside `node(...)` / `deploymentNode(...)`; any such metadata
 *   becomes a `// TODO` instead.
 * - [ContainerDiagram] / [ComponentDiagram] — the builders only expose
 *   coarse `show*` boolean flags plus `exclude(...)`, never a per-element
 *   `include(...)` for external systems/persons/containers. The printer
 *   reimplements the builders' own filter logic to find the best reachable
 *   `system =` / `container =` + flags + `exclude(...)` combination and
 *   verifies it reproduces the stored `elements`/`relationships`; on any
 *   residual mismatch it still emits the best-effort call and appends a
 *   `// TODO` naming the missing/extra element ids.
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
        sb.appendLine("c4Model(name = ${quote(model.name)}) {")
        model.description?.let { modelDescription ->
            sb.appendLine(
                "    // TODO: C4Model.description ${quote(modelDescription)} not serialized — " +
                    "the c4Model(...) DSL builder ignores its `description` parameter " +
                    "(see C4ModelBuilder.build()); C4Model.description cannot currently be set via the DSL.",
            )
        }

        val elementById: Map<ElementId, C4Element> = model.elements.associateBy { it.id }
        val allDeploymentNodes = model.elements.filterIsInstance<C4DeploymentNode>()
        val childNodeIds = allDeploymentNodes.flatMap { it.children }.toSet()
        val topLevelIds =
            model.elements
                .filter { it is C4Person || it is C4SoftwareSystem || (it is C4DeploymentNode && it.id !in childNodeIds) }
                .map { it.id }
                .toSet()
        val orderedTop = model.elements.filter { it.id in topLevelIds }

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

        val nonCode = model.elements.filterNot { it is C4CodeElement }
        nonCode.filterNot { it.id in ctx.printed }.forEach { el ->
            sb.appendLine(
                "    // TODO: ${el::class.simpleName} ${quote(el.name)} (id = ${quote(el.id)}) not serialized — " +
                    "element is not reachable from any top-level DSL entry point " +
                    "(orphaned container/component, or a dangling parent reference).",
            )
        }
        model.elements.filterIsInstance<C4CodeElement>().forEach { ce ->
            sb.appendLine(
                "    // TODO: C4CodeElement ${quote(ce.name)} (id = ${quote(ce.id)}) not serialized — " +
                    "no DSL builder exists for C4CodeElement (C4ModelBuilder has no codeElement() entry point).",
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
        for (comp in comps) printComponent(sb = inner, indent = "$indent    ", c = comp, ctx = ctx)
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
        ctx: Ctx,
    ) {
        ctx.printed += c.id
        val assignedVar = ctx.varNames[c.id]
        val prefix = if (assignedVar != null) "$indent$assignedVar = " else indent
        val inner = StringBuilder()
        c.description?.let { inner.appendLine("$indent    description = ${quote(it)}") }
        c.technology?.let { inner.appendLine("$indent    technology = ${quote(it)}") }
        printLayoutHints(sb = inner, metadata = c.metadata, indent = "$indent    ")
        if (inner.isBlank()) {
            sb.appendLine("${prefix}component(${quote(c.name)})")
        } else {
            sb.appendLine("${prefix}component(${quote(c.name)}) {")
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
    ) {
        ctx.printed += n.id
        val prefix = if (varName != null) "$indent$varName = " else indent
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
            inner.appendLine(
                "$indent    containerInstance(${quote(ci.name)}, containerId = \"\") " +
                    "// NOTE: containerId is ignored by DeploymentNodeScopeImpl.containerInstance()",
            )
        }
        if (hasLayoutHints(n.metadata)) {
            inner.appendLine(
                "$indent    // TODO: layout metadata on C4DeploymentNode ${quote(n.name)} not serialized — " +
                    "DeploymentNodeScope does not extend LayoutHintsScope, so no layout { … } call is available here.",
            )
        }
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

    private fun computeContainerDiagramResult(
        model: C4Model,
        systemId: ElementId,
        excluded: Set<ElementId>,
        showExternalSystems: Boolean,
        showRelatedPersons: Boolean,
        showRelationships: Boolean,
    ): Pair<Set<ElementId>, Set<ElementId>> {
        val systemContainers =
            model.elements
                .filterIsInstance<C4Container>()
                .filter { it.system == systemId && it.id !in excluded }
                .map { it.id }
        val externalSystems =
            if (showExternalSystems) {
                findExternalSystemsFor(
                    model = model,
                    systemId = systemId,
                    systemContainers = systemContainers,
                )
            } else {
                emptySet()
            }
        val relatedPersons =
            if (showRelatedPersons) {
                findRelatedPersonsFor(
                    model = model,
                    systemId = systemId,
                    systemContainers = systemContainers,
                )
            } else {
                emptySet()
            }
        val allElements = (setOf(systemId) + systemContainers + externalSystems + relatedPersons)
        val filteredRelationships =
            if (showRelationships) {
                model.relationships
                    .filter { it.source in allElements && it.target in allElements }
                    .map { it.id }
                    .toSet()
            } else {
                emptySet()
            }
        return allElements to filteredRelationships
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
        val actualExternalSystems = externalPresent.filter { id -> model.elements.find { it.id == id } is C4SoftwareSystem }.toSet()
        val actualPersons = externalPresent.filter { id -> model.elements.find { it.id == id } is C4Person }.toSet()

        val showExternalSystems = actualExternalSystems.isNotEmpty()
        val showRelatedPersons = actualPersons.isNotEmpty()

        val (elementsWithRels, relsAllOn) =
            computeContainerDiagramResult(
                model = model,
                systemId = d.system,
                excluded = excluded,
                showExternalSystems = showExternalSystems,
                showRelatedPersons = showRelatedPersons,
                showRelationships = true,
            )
        val showRelationships = if (d.relationships.isEmpty() && relsAllOn.isNotEmpty()) false else true
        val (finalElements, finalRelationships) =
            if (showRelationships) {
                elementsWithRels to relsAllOn
            } else {
                computeContainerDiagramResult(
                    model = model,
                    systemId = d.system,
                    excluded = excluded,
                    showExternalSystems = showExternalSystems,
                    showRelatedPersons = showRelatedPersons,
                    showRelationships = false,
                )
            }

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
        val matches = finalElements == diagElements && finalRelationships == d.relationships.toSet()
        if (!matches) {
            sb.appendLine(
                "        // TODO: ContainerDiagram ${quote(d.name)} could not be reconstructed exactly via the C4 DSL " +
                    "builder API (only global show*/exclude() flags are available, no per-element include()). " +
                    "Missing from reconstruction: ${sanitizeForComment(diagElements - finalElements)}. " +
                    "Extra in reconstruction: ${sanitizeForComment(finalElements - diagElements)}.",
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

    private fun computeComponentDiagramResult(
        model: C4Model,
        containerId: ElementId,
        excluded: Set<ElementId>,
        showExternalReferences: Boolean,
        showRelationships: Boolean,
    ): Pair<Set<ElementId>, Set<ElementId>> {
        val comps =
            model.elements
                .filterIsInstance<C4Component>()
                .filter { it.container == containerId && it.id !in excluded }
                .map { it.id }
        val ext =
            if (showExternalReferences) {
                findExternalContainersFor(
                    model = model,
                    containerId = containerId,
                    containerComponents = comps,
                )
            } else {
                emptySet()
            }
        val allElements = setOf(containerId) + comps + ext
        val filteredRels =
            if (showRelationships) {
                model.relationships
                    .filter { it.source in allElements && it.target in allElements }
                    .map { it.id }
                    .toSet()
            } else {
                emptySet()
            }
        return allElements to filteredRels
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
        val showExternalReferences = externalPresent.isNotEmpty()

        val (elementsWithRels, relsAllOn) =
            computeComponentDiagramResult(
                model = model,
                containerId = d.container,
                excluded = excluded,
                showExternalReferences = showExternalReferences,
                showRelationships = true,
            )
        val showRelationships = if (d.relationships.isEmpty() && relsAllOn.isNotEmpty()) false else true
        val (finalElements, finalRelationships) =
            if (showRelationships) {
                elementsWithRels to relsAllOn
            } else {
                computeComponentDiagramResult(
                    model = model,
                    containerId = d.container,
                    excluded = excluded,
                    showExternalReferences = showExternalReferences,
                    showRelationships = false,
                )
            }

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
        val matches = finalElements == diagElements && finalRelationships == d.relationships.toSet()
        if (!matches) {
            sb.appendLine(
                "        // TODO: ComponentDiagram ${quote(d.name)} could not be reconstructed exactly via the C4 DSL " +
                    "builder API (only global show*/exclude() flags are available, no per-element include()). " +
                    "Missing from reconstruction: ${sanitizeForComment(diagElements - finalElements)}. " +
                    "Extra in reconstruction: ${sanitizeForComment(finalElements - diagElements)}.",
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

    // Only element kinds whose scope actually exposes a `layout { … }` DSL entry point (see
    // printLayoutHints call sites above) are considered here. C4DeploymentNode carries layout
    // metadata too, but DeploymentNodeScope has no `layout { … }` entry point — printDeploymentNode
    // falls back to a `// TODO` comment instead (see hasLayoutHints usage there) — so a model whose
    // only layout-hint-bearing element is a deployment node must NOT trigger this import: no
    // `layout { … }` call is ever emitted, and the import would be unused dead code.
    private fun hasAnyPrintableLayoutHints(elements: List<C4Element>): Boolean =
        elements.any { el ->
            val meta =
                when (el) {
                    is C4Person -> el.metadata
                    is C4SoftwareSystem -> el.metadata
                    is C4Container -> el.metadata
                    is C4Component -> el.metadata
                    // No `layout { … }` DSL entry point exists for deployment nodes — see comment above.
                    is C4DeploymentNode -> emptyMap()
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
