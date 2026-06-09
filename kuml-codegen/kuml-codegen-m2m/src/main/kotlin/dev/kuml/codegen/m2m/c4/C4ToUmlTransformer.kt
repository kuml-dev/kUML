package dev.kuml.codegen.m2m.c4

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.KumlTransformer
import dev.kuml.codegen.m2m.KumlTransformerProvider
import dev.kuml.codegen.m2m.TraceabilityLink
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.TransformTrace

/**
 * Transforms a [C4Model] into a kUML class diagram script (`uml-from-c4.kuml.kts`).
 *
 * Mapping rules:
 * - Each non-external [C4SoftwareSystem] → `classOf` with stereotype `"system"`
 * - Each external [C4SoftwareSystem] → `classOf` with stereotypes `"system"` + `"external"`
 * - Each [C4Container] belonging to the first software system → `classOf` with stereotype `"container"`
 * - Each [C4Component] belonging to any mapped container → `classOf` with stereotype `"component"`
 * - Each [C4Person] (when `includePersons=true`) → `classOf` with stereotype `"actor"` (+ `"external"` if external)
 * - Each [C4Relationship] where both endpoints are mapped → `dependency(source = …, target = …)`
 * - Relationships where only one end is mapped are silently skipped
 *
 * Name conversion: `"Web Application"` → `"WebApplication"` (spaces removed, first letter of each
 * word capitalised, joined without separator — effectively PascalCase from space-separated words).
 *
 * Output: a single [GeneratedFile] at path `"uml-from-c4.kuml.kts"` containing
 * a ready-to-render kUML class diagram script.
 *
 * Options (via [TransformContext.options]):
 * - `"includePersons"` — `"true"` to include [C4Person] elements, default `"false"`
 * - `"diagramName"` — override diagram name, default `"<modelName> — UML"`
 *
 * V2.x deferred: multi-system inclusion, container-level scoping, relationship labels,
 * technology as a typed attribute reference.
 */
public class C4ToUmlTransformer : KumlTransformer<C4Model, List<GeneratedFile>> {
    override val id: String = "c4-to-uml"
    override val description: String =
        "C4 model → UML class diagram script (systems, containers, components as classes)"

    override fun transform(
        source: C4Model,
        ctx: TransformContext,
    ): TransformResult<List<GeneratedFile>> {
        val includePersons = ctx.options["includePersons"] == "true"
        val diagramName = ctx.options["diagramName"] ?: "${source.name} — UML"

        // ── Index all elements by id ──────────────────────────────────────────
        val allElementsById =
            source.elements.associateBy { it.id } +
                mapOf(source.id to source)

        // ── Identify the primary software system (first non-external, or first) ─
        val softwareSystems = source.elements.filterIsInstance<C4SoftwareSystem>()
        val primarySystem = softwareSystems.firstOrNull { !it.external } ?: softwareSystems.firstOrNull()

        // ── Collect elements to map ───────────────────────────────────────────
        // id → (varName, stereotype(s))
        val elementEntries = mutableListOf<ElementEntry>()

        // Software systems (all — external gets extra stereotype)
        for (system in softwareSystems) {
            val stereotypes =
                if (system.external) {
                    listOf("system", "external")
                } else {
                    listOf("system")
                }
            elementEntries +=
                ElementEntry(
                    id = system.id,
                    varName = toPascalCase(system.name),
                    displayName = toPascalCase(system.name),
                    stereotypes = stereotypes,
                    technology = null,
                )
        }

        // Containers — only from primary system's container ids
        if (primarySystem != null) {
            val containerIds = primarySystem.containers.toSet()
            val containers =
                source.elements
                    .filterIsInstance<C4Container>()
                    .filter { it.id in containerIds || it.system == primarySystem.id }
            for (container in containers) {
                elementEntries +=
                    ElementEntry(
                        id = container.id,
                        varName = toPascalCase(container.name),
                        displayName = toPascalCase(container.name),
                        stereotypes = listOf("container"),
                        technology = container.technology,
                    )
            }

            // Components — belonging to any of the above containers
            val mappedContainerIds = containers.map { it.id }.toSet()
            val components =
                source.elements
                    .filterIsInstance<C4Component>()
                    .filter { it.container in mappedContainerIds }
            for (component in components) {
                elementEntries +=
                    ElementEntry(
                        id = component.id,
                        varName = toPascalCase(component.name),
                        displayName = toPascalCase(component.name),
                        stereotypes = listOf("component"),
                        technology = component.technology,
                    )
            }
        } else {
            // No software system — still include all containers and components
            for (container in source.elements.filterIsInstance<C4Container>()) {
                elementEntries +=
                    ElementEntry(
                        id = container.id,
                        varName = toPascalCase(container.name),
                        displayName = toPascalCase(container.name),
                        stereotypes = listOf("container"),
                        technology = container.technology,
                    )
            }
            for (component in source.elements.filterIsInstance<C4Component>()) {
                elementEntries +=
                    ElementEntry(
                        id = component.id,
                        varName = toPascalCase(component.name),
                        displayName = toPascalCase(component.name),
                        stereotypes = listOf("component"),
                        technology = component.technology,
                    )
            }
        }

        // Persons (opt-in)
        if (includePersons) {
            for (person in source.elements.filterIsInstance<C4Person>()) {
                val stereotypes =
                    if (person.external) {
                        listOf("actor", "external")
                    } else {
                        listOf("actor")
                    }
                elementEntries +=
                    ElementEntry(
                        id = person.id,
                        varName = toPascalCase(person.name),
                        displayName = toPascalCase(person.name),
                        stereotypes = stereotypes,
                        technology = null,
                    )
            }
        }

        // ── Disambiguate duplicate varNames ───────────────────────────────────
        val usedVarNames = mutableMapOf<String, Int>()
        for (entry in elementEntries) {
            val count = usedVarNames.getOrDefault(entry.varName, 0) + 1
            usedVarNames[entry.varName] = count
        }
        val seen = mutableMapOf<String, Int>()
        for (entry in elementEntries) {
            val total = usedVarNames[entry.varName] ?: 1
            if (total > 1) {
                val idx = (seen.getOrDefault(entry.varName, 0)) + 1
                seen[entry.varName] = idx
                entry.varName = "${entry.varName}$idx"
            }
        }

        // id → varName lookup for relationship generation
        val idToVarName = elementEntries.associate { it.id to it.varName }

        // ── Build the script ──────────────────────────────────────────────────
        val sb = StringBuilder()

        sb.appendLine("// Generated by kUML C4→UML transformer — do not edit manually.")
        sb.appendLine("// Source: ${source.name}")
        sb.appendLine()
        sb.appendLine("""classDiagram(name = "$diagramName") {""")
        sb.appendLine()

        var trace = TransformTrace()

        for (entry in elementEntries) {
            sb.appendLine("    val ${entry.varName} = classOf(name = \"${entry.displayName}\") {")
            for (stereotype in entry.stereotypes) {
                sb.appendLine("""        stereotypes += "$stereotype"""")
            }
            if (!entry.technology.isNullOrBlank()) {
                sb.appendLine("""        attribute(name = "technology", type = "${entry.technology}")""")
            }
            sb.appendLine("    }")
            sb.appendLine()
            trace = trace.plus(TraceabilityLink(entry.id, OUTPUT_PATH, RULE_ELEMENT_TO_CLASS))
        }

        // ── Relationships ─────────────────────────────────────────────────────
        val relationships = source.relationships.filterIsInstance<C4Relationship>()
        val emittedDeps = mutableListOf<String>()
        for (rel in relationships) {
            val sourceVar = idToVarName[rel.source]
            val targetVar = idToVarName[rel.target]
            if (sourceVar != null && targetVar != null) {
                emittedDeps += "    dependency(source = $sourceVar, target = $targetVar)"
                trace = trace.plus(TraceabilityLink(rel.id, OUTPUT_PATH, RULE_RELATIONSHIP_TO_DEPENDENCY))
            }
            // silently skip when one end is not mapped
        }

        if (emittedDeps.isNotEmpty()) {
            for (dep in emittedDeps) {
                sb.appendLine(dep)
            }
            sb.appendLine()
        }

        sb.append("}")
        sb.appendLine()

        val content = sb.toString()
        val file = GeneratedFile(OUTPUT_PATH, content)

        return TransformResult.Success(listOf(file), trace)
    }

    // ── Name conversion ───────────────────────────────────────────────────────

    /**
     * Converts a space-separated (or already-joined) name to PascalCase without spaces.
     *
     * Examples:
     * - `"Web Application"` → `"WebApplication"`
     * - `"API Server"` → `"APIServer"`
     * - `"database"` → `"Database"`
     * - `"WebApplication"` → `"WebApplication"` (idempotent)
     */
    internal fun toPascalCase(name: String): String =
        name
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString("") { word ->
                word[0].uppercaseChar() + word.drop(1)
            }

    private companion object {
        const val OUTPUT_PATH = "uml-from-c4.kuml.kts"
        const val RULE_ELEMENT_TO_CLASS = "c4-element-to-uml-class"
        const val RULE_RELATIONSHIP_TO_DEPENDENCY = "c4-relationship-to-uml-dependency"
    }
}

/** Mutable working record for a single C4 element being mapped. */
private data class ElementEntry(
    val id: String,
    var varName: String,
    val displayName: String,
    val stereotypes: List<String>,
    val technology: String?,
)

/** ServiceLoader provider for [C4ToUmlTransformer]. */
public class C4ToUmlTransformerProvider : KumlTransformerProvider {
    override fun transformer(): C4ToUmlTransformer = C4ToUmlTransformer()
}
