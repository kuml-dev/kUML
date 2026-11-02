package dev.kuml.sysml2.constraint

import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2ImportTarget
import dev.kuml.sysml2.Sysml2Limits
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.Sysml2PackageDiagram

/** Severity of a [PackageIssue]. */
public enum class PackageSeverity { Error, Warning }

/**
 * A finding of the package checker (wave 2I-a).
 *
 * @property code stable code `KUML-PKG-E0xx` (error) or `KUML-PKG-W0xx` (warning).
 * @property elementId id of the offending package, import, view or diagram element.
 * @property hint how to fix it.
 */
public data class PackageIssue(
    val code: String,
    val severity: PackageSeverity,
    val elementId: String,
    val message: String,
    val hint: String,
)

/**
 * Structural checker for packages, imports, views and viewpoints (wave 2I-a).
 *
 * | Code | Severity | Rule |
 * |------|----------|------|
 * | KUML-PKG-E001 | Error | package cycle, or a package whose parent does not exist |
 * | KUML-PKG-E002 | Error | a view satisfies a viewpoint that does not exist |
 * | KUML-PKG-E003 | Error | a limit is exceeded (packages, package depth, imports, views, viewpoints, entries per view / viewpoint) |
 * | KUML-PKG-W001 | Warning | an import target resolves neither to a package nor to a member definition |
 * | KUML-PKG-W002 | Warning | a package lists a member id that is not a definition |
 * | KUML-PKG-W003 | Warning | the package diagram lists an id that is no package, definition, view or viewpoint |
 * | KUML-PKG-W004 | Warning | an import points at an ancestor or a descendant of its own package (drawn as a text line, not as an arrow) |
 * | KUML-PKG-W005 | Warning | a package is nested deeper than [Sysml2Limits.PACKAGE_DEPTH_FRAMES] levels inside the package diagram that draws it (drawn as a collapsed card) |
 *
 * All traversals are iterative and bounded, so cyclic or hostile models cannot hang the checker.
 */
public object Sysml2PackageChecker {
    /**
     * Checks the packages of [model]. With a [diagram], the diagram-specific rules (W003, W005 for drawn packages)
     * run as well; the model-level rules always run. Findings come in a deterministic order.
     */
    public fun check(
        model: Sysml2Model,
        diagram: Sysml2Diagram? = null,
    ): List<PackageIssue> {
        val issues = mutableListOf<PackageIssue>()
        val index = model.packageIndex()

        fun error(
            code: String,
            id: String,
            message: String,
            hint: String,
        ) {
            issues += PackageIssue(code = code, severity = PackageSeverity.Error, elementId = id, message = message, hint = hint)
        }

        fun warn(
            code: String,
            id: String,
            message: String,
            hint: String,
        ) {
            issues += PackageIssue(code = code, severity = PackageSeverity.Warning, elementId = id, message = message, hint = hint)
        }

        if (model.packages.size > Sysml2Limits.PACKAGES) {
            error("KUML-PKG-E003", "packages", "More than ${Sysml2Limits.PACKAGES} packages.", "Split the model into smaller ones.")
        }
        if (model.imports.size > Sysml2Limits.IMPORTS) {
            error("KUML-PKG-E003", "imports", "More than ${Sysml2Limits.IMPORTS} imports.", "Remove imports that no one needs.")
        }
        if (model.views.size > Sysml2Limits.VIEWS || model.viewpoints.size > Sysml2Limits.VIEWS) {
            error("KUML-PKG-E003", "views", "More than ${Sysml2Limits.VIEWS} views or viewpoints.", "Remove views that no one needs.")
        }
        for (p in model.packages.take(Sysml2Limits.PACKAGES)) {
            val depth = index.depthOf(p)
            when {
                depth == Int.MAX_VALUE ->
                    error("KUML-PKG-E001", p.id, "Package '${p.name}' is part of a package cycle.", "Make the package tree acyclic.")
                p.parentId?.let { index.packageById(it) == null } == true ->
                    error(
                        "KUML-PKG-E001",
                        p.id,
                        "Package '${p.name}' has the unknown parent '${p.parentId}'.",
                        "Use an existing package id.",
                    )
                depth > Sysml2Limits.PACKAGE_DEPTH ->
                    error(
                        "KUML-PKG-E003",
                        p.id,
                        "Package '${p.name}' is nested ${Sysml2Limits.PACKAGE_DEPTH + 1} or more levels deep.",
                        "Flatten the package tree to at most ${Sysml2Limits.PACKAGE_DEPTH} levels.",
                    )
            }
            for (m in p.memberIds) {
                if (index.definitionById(m) == null) {
                    warn("KUML-PKG-W002", p.id, "Package '${p.name}' lists the unknown member '$m'.", "Use the id of a definition.")
                }
            }
        }
        for (imp in model.imports.take(Sysml2Limits.IMPORTS)) {
            val owner = index.packageById(imp.ownerPackageId)
            if (owner == null) {
                error(
                    "KUML-PKG-E001",
                    imp.id,
                    "Import '${imp.rawTarget}' belongs to the unknown package '${imp.ownerPackageId}'.",
                    "Declare it inside a package.",
                )
                continue
            }
            val target = index.resolveImportTarget(imp)
            if (target == null) {
                warn(
                    "KUML-PKG-W001",
                    imp.id,
                    "Import '${imp.rawTarget}' in package '${index.qualifiedNameOf(owner)}' names no package or member.",
                    "Check the qualified name; the import is shown as a text line.",
                )
                continue
            }
            if (target is Sysml2ImportTarget.PackageTarget) {
                val t = target.pkg
                val related =
                    t.id == owner.id || index.ancestorsOf(owner).any { it.id == t.id } || index.ancestorsOf(t).any { it.id == owner.id }
                if (related) {
                    warn(
                        "KUML-PKG-W004",
                        imp.id,
                        "Import '${imp.rawTarget}' points at the own package or an ancestor / descendant of it.",
                        "It is drawn as a text line in the frame, not as an arrow.",
                    )
                }
            }
        }
        val viewpointIds = model.viewpoints.map { it.id }.toSet()
        for (v in model.views.take(Sysml2Limits.VIEWS)) {
            for (vp in v.viewpointIds) {
                if (vp !in viewpointIds) {
                    error(
                        "KUML-PKG-E002",
                        v.id,
                        "View '${v.name}' satisfies the unknown viewpoint '$vp'.",
                        "Declare the viewpoint or fix the name.",
                    )
                }
            }
            if (v.exposes.size > Sysml2Limits.VIEW_ENTRIES || v.filters.size > Sysml2Limits.VIEW_ENTRIES) {
                error(
                    "KUML-PKG-E003",
                    v.id,
                    "View '${v.name}' has more than ${Sysml2Limits.VIEW_ENTRIES} expose or filter entries.",
                    "Shorten the lists.",
                )
            }
        }
        for (vp in model.viewpoints.take(Sysml2Limits.VIEWS)) {
            if (vp.stakeholders.size > Sysml2Limits.VIEW_ENTRIES || vp.concerns.size > Sysml2Limits.VIEW_ENTRIES) {
                error(
                    "KUML-PKG-E003",
                    vp.id,
                    "Viewpoint '${vp.name}' has more than ${Sysml2Limits.VIEW_ENTRIES} stakeholders or concerns.",
                    "Shorten the lists.",
                )
            }
        }
        val pd = diagram as? Sysml2PackageDiagram
        if (pd != null) {
            val known = (model.packages.map { it.id } + model.definitions.map { it.id } + model.views.map { it.id } + viewpointIds).toSet()
            for (id in pd.elementIds) {
                if (id !in known) {
                    warn(
                        "KUML-PKG-W003",
                        id,
                        "The package diagram '${pd.name}' lists the unknown id '$id'.",
                        "Use a package, definition, view or viewpoint id.",
                    )
                    continue
                }
                val pkg = index.packageById(id) ?: continue
                val rootDepth = index.depthOf(pkg)
                if (rootDepth == Int.MAX_VALUE) continue
                for (p in index.subtreeOf(pkg)) {
                    // Depth inside the diagram: 1 for the package that is included directly.
                    val relative = index.depthOf(p) - rootDepth + 1
                    if (relative > Sysml2Limits.PACKAGE_DEPTH_FRAMES && index.depthOf(p) != Int.MAX_VALUE) {
                        warn(
                            "KUML-PKG-W005",
                            p.id,
                            "Package '${index.qualifiedNameOf(
                                p,
                            )}' is nested deeper than ${Sysml2Limits.PACKAGE_DEPTH_FRAMES} levels in this diagram.",
                            "It is drawn as a collapsed card inside its level-${Sysml2Limits.PACKAGE_DEPTH_FRAMES} package.",
                        )
                    }
                }
            }
        }
        return issues.distinct()
    }
}
