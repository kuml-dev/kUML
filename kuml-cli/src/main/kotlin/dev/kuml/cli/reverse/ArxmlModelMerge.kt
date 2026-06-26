package dev.kuml.cli.reverse

import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlPackage
import java.util.UUID

/**
 * Merges a list of [KumlModel] instances produced by importing multiple ARXML files into one
 * consolidated [KumlModel].
 *
 * **Strategy**: AUTOSAR projects routinely split one logical AR-PACKAGE tree across multiple
 * `.arxml` files (e.g. one file per software component, one file for all interfaces). A naïve
 * merge would produce duplicate AR-PACKAGE elements on export, causing schema-validation errors.
 * This object merges packages with the **same qualified name** by unioning their members
 * recursively. Non-package members (components, interfaces) with the same name in the same
 * package are deduplicated — the first occurrence wins.
 *
 * The merge operates purely on [KumlModel] / [UmlPackage] / [dev.kuml.uml.UmlNamedElement]
 * types from `kuml-io-uml`, which IS on the CLI classpath. No `dev.kuml.io.arxml.*` types
 * are imported at compile time in this file — the importers are reached only via reflection.
 *
 * V3.1.36 — initial implementation.
 */
internal object ArxmlModelMerge {
    /**
     * Merges [models] into a single [KumlModel].
     *
     * Each model's root is expected to be a [UmlPackage] (as produced by [dev.kuml.io.arxml.ArxmlClassicImporter]).
     * The returned model's root is a synthetic root [UmlPackage] whose children are the merged
     * top-level package trees.
     *
     * When [models] is empty, returns an empty model with a synthetic root named "AutosarProject".
     */
    internal fun merge(models: List<KumlModel>): KumlModel {
        if (models.isEmpty()) {
            val emptyRoot =
                UmlPackage(
                    id = UUID.randomUUID().toString(),
                    name = "AutosarProject",
                    members = emptyList(),
                )
            return KumlModel(
                root = emptyRoot,
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = "AutosarProject",
            )
        }

        // Collect all top-level package members from every model root.
        // Each model root produced by ArxmlClassicImporter is a UmlPackage named "AUTOSAR"
        // wrapping the actual top-level AR-PACKAGEs as its members.
        val allTopLevelMembers: List<UmlNamedElement> =
            models.flatMap { model ->
                val root = model.root
                if (root is UmlPackage) {
                    // The root is the synthetic "AUTOSAR" wrapper — unwrap its children.
                    root.members
                } else if (root is UmlNamedElement) {
                    listOf(root)
                } else {
                    emptyList()
                }
            }

        // Merge the top-level package list, deduplicating by name.
        val mergedTopLevel = mergeMembers(allTopLevelMembers)

        val projectName = (models.firstOrNull()?.name) ?: "AutosarProject"
        val mergedRoot =
            UmlPackage(
                id = UUID.randomUUID().toString(),
                name = "AUTOSAR",
                members = mergedTopLevel,
            )
        return KumlModel(
            root = mergedRoot,
            language = ModelingLanguage.UML,
            level = ModelLevel.PIM,
            name = projectName,
        )
    }

    /**
     * Merges a list of [UmlNamedElement] instances, deduplicating [UmlPackage]s by name via
     * recursive member-union. Non-package elements with the same name are deduplicated by keeping
     * the first occurrence.
     */
    private fun mergeMembers(members: List<UmlNamedElement>): List<UmlNamedElement> {
        val result = mutableListOf<UmlNamedElement>()
        // Index of already-seen package names → position in result list.
        val pkgIndex = mutableMapOf<String, Int>()
        // Track non-package element names to deduplicate.
        val seenNames = mutableSetOf<String>()

        for (member in members) {
            if (member is UmlPackage) {
                val existingIdx = pkgIndex[member.name]
                if (existingIdx != null) {
                    // Merge the new package's members into the existing one.
                    val existing = result[existingIdx] as UmlPackage
                    val merged = mergePackages(existing, member)
                    result[existingIdx] = merged
                } else {
                    pkgIndex[member.name] = result.size
                    result.add(member)
                }
            } else {
                // Non-package element: deduplicate by name — first occurrence wins.
                if (seenNames.add(member.name)) {
                    result.add(member)
                }
            }
        }
        return result
    }

    /**
     * Merges [additional] into [base] by recursively unioning their members.
     * Returns a new [UmlPackage] with the combined member list.
     */
    private fun mergePackages(
        base: UmlPackage,
        additional: UmlPackage,
    ): UmlPackage {
        val combinedMembers = mergeMembers(base.members + additional.members)
        return UmlPackage(
            id = base.id,
            name = base.name,
            members = combinedMembers,
        )
    }
}
