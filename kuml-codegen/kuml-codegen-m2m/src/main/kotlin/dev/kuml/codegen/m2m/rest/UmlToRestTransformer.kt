package dev.kuml.codegen.m2m.rest

import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.KumlTransformer
import dev.kuml.codegen.m2m.KumlTransformerProvider
import dev.kuml.codegen.m2m.TraceabilityLink
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.TransformTrace
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass

/**
 * Transforms a UML class diagram into an OpenAPI 3.0 YAML specification.
 *
 * For each [UmlClass] in the diagram:
 * - A JSON Schema component is emitted under `components/schemas/<ClassName>`
 * - Attributes become JSON Schema properties with mapped types
 * - Five CRUD endpoints are generated: GET list, GET by id, POST, PUT, DELETE
 *
 * Output: a single [GeneratedFile] with path `openapi.yaml`.
 *
 * Options (via [TransformContext.options]):
 * - `"basePath"` — API base path prefix, default `/api/v1`
 * - `"title"` — API title, default `"Generated API"`
 * - `"version"` — API version string, default `"1.0.0"`
 *
 * V2.x deferred: relationships → `$ref` cross-references, authentication schemes,
 * request-body validation constraints, pagination parameters.
 */
public class UmlToRestTransformer : KumlTransformer<KumlDiagram, List<GeneratedFile>> {
    override val id: String = "uml-to-rest"
    override val description: String =
        "UML class diagram → OpenAPI 3.0 YAML (CRUD endpoints + JSON Schema components)"

    override fun transform(
        source: KumlDiagram,
        ctx: TransformContext,
    ): TransformResult<List<GeneratedFile>> {
        val basePath = ctx.options["basePath"] ?: DEFAULT_BASE_PATH
        val title = ctx.options["title"] ?: DEFAULT_TITLE
        val version = ctx.options["version"] ?: DEFAULT_VERSION

        val classes = source.elements.filterIsInstance<UmlClass>()
        var trace = TransformTrace()

        val sb = StringBuilder()

        // ── OpenAPI header ────────────────────────────────────────────────────
        sb.appendLine("""openapi: "3.0.3"""")
        sb.appendLine("info:")
        sb.appendLine("  title: $title")
        sb.appendLine("""  version: "$version"""")

        // ── Paths ─────────────────────────────────────────────────────────────
        sb.appendLine("paths:")

        for (cls in classes) {
            val resourcePath = toKebabCasePlural(cls.name)
            val fullPath = "$basePath/$resourcePath"
            val className = cls.name
            val schemaRef = "#/components/schemas/$className"

            // GET list + POST
            sb.appendLine("  $fullPath:")

            // GET list
            val classNamePlural = toSimplePlural(className)
            sb.appendLine("    get:")
            sb.appendLine("      operationId: list$classNamePlural")
            sb.appendLine("      summary: List all $classNamePlural")
            sb.appendLine("      responses:")
            sb.appendLine("""        "200":""")
            sb.appendLine("          description: OK")
            sb.appendLine("          content:")
            sb.appendLine("            application/json:")
            sb.appendLine("              schema:")
            sb.appendLine("                type: array")
            sb.appendLine("                items:")
            sb.appendLine("""                  ${'$'}ref: "$schemaRef"""")

            // POST
            sb.appendLine("    post:")
            sb.appendLine("      operationId: create$className")
            sb.appendLine("      summary: Create a $className")
            sb.appendLine("      requestBody:")
            sb.appendLine("        required: true")
            sb.appendLine("        content:")
            sb.appendLine("          application/json:")
            sb.appendLine("            schema:")
            sb.appendLine("""              ${'$'}ref: "$schemaRef"""")
            sb.appendLine("      responses:")
            sb.appendLine("""        "201":""")
            sb.appendLine("          description: Created")
            sb.appendLine("          content:")
            sb.appendLine("            application/json:")
            sb.appendLine("              schema:")
            sb.appendLine("""                ${'$'}ref: "$schemaRef"""")

            // GET by id + PUT + DELETE
            sb.appendLine("  $fullPath/{id}:")

            // GET by id
            sb.appendLine("    get:")
            sb.appendLine("      operationId: get$className")
            sb.appendLine("      summary: Get $className by id")
            sb.appendLine("      parameters:")
            sb.appendLine("        - name: id")
            sb.appendLine("          in: path")
            sb.appendLine("          required: true")
            sb.appendLine("          schema:")
            sb.appendLine("            type: string")
            sb.appendLine("      responses:")
            sb.appendLine("""        "200":""")
            sb.appendLine("          description: OK")
            sb.appendLine("          content:")
            sb.appendLine("            application/json:")
            sb.appendLine("              schema:")
            sb.appendLine("""                ${'$'}ref: "$schemaRef"""")
            sb.appendLine("""        "404":""")
            sb.appendLine("          description: Not Found")

            // PUT
            sb.appendLine("    put:")
            sb.appendLine("      operationId: update$className")
            sb.appendLine("      summary: Update $className")
            sb.appendLine("      parameters:")
            sb.appendLine("        - name: id")
            sb.appendLine("          in: path")
            sb.appendLine("          required: true")
            sb.appendLine("          schema:")
            sb.appendLine("            type: string")
            sb.appendLine("      requestBody:")
            sb.appendLine("        required: true")
            sb.appendLine("        content:")
            sb.appendLine("          application/json:")
            sb.appendLine("            schema:")
            sb.appendLine("""              ${'$'}ref: "$schemaRef"""")
            sb.appendLine("      responses:")
            sb.appendLine("""        "200":""")
            sb.appendLine("          description: OK")
            sb.appendLine("          content:")
            sb.appendLine("            application/json:")
            sb.appendLine("              schema:")
            sb.appendLine("""                ${'$'}ref: "$schemaRef"""")

            // DELETE
            sb.appendLine("    delete:")
            sb.appendLine("      operationId: delete$className")
            sb.appendLine("      summary: Delete $className")
            sb.appendLine("      parameters:")
            sb.appendLine("        - name: id")
            sb.appendLine("          in: path")
            sb.appendLine("          required: true")
            sb.appendLine("          schema:")
            sb.appendLine("            type: string")
            sb.appendLine("      responses:")
            sb.appendLine("""        "204":""")
            sb.append("          description: No Content")

            // Blank line between path groups (except after last)
            if (cls !== classes.last()) {
                sb.appendLine()
                sb.appendLine()
            }

            trace = trace.plus(TraceabilityLink(cls.id, "openapi.yaml", RULE_CLASS_TO_PATH))
        }

        // ── Components / Schemas ──────────────────────────────────────────────
        sb.appendLine()
        sb.appendLine("components:")
        sb.appendLine("  schemas:")

        for ((index, cls) in classes.withIndex()) {
            sb.appendLine("    ${cls.name}:")
            sb.appendLine("      type: object")

            if (cls.attributes.isEmpty()) {
                sb.appendLine("      properties: {}")
            } else {
                sb.appendLine("      properties:")
                for (attr in cls.attributes) {
                    val (type, format) = openApiType(attr.type.name)
                    sb.appendLine("        ${attr.name}:")
                    sb.appendLine("          type: $type")
                    if (format != null) {
                        sb.appendLine("          format: $format")
                    }
                }
            }

            if (index < classes.lastIndex) {
                // no trailing blank line after last schema
            }
        }

        val content = sb.toString().trimEnd() + "\n"
        val file = GeneratedFile("openapi.yaml", content)

        return TransformResult.Success(listOf(file), trace)
    }

    // ── Type mapping ──────────────────────────────────────────────────────────

    /**
     * Maps a UML attribute type name to an OpenAPI (type, format?) pair.
     */
    private fun openApiType(umlType: String): Pair<String, String?> =
        when (umlType.lowercase()) {
            "string" -> "string" to null
            "integer", "int", "long" -> "integer" to "int64"
            "boolean", "bool" -> "boolean" to null
            "double", "float" -> "number" to "double"
            "uuid" -> "string" to "uuid"
            "localdate", "date" -> "string" to "date"
            else -> "string" to null
        }

    // ── Name conversion ───────────────────────────────────────────────────────

    /**
     * Converts a PascalCase class name to a kebab-case plural resource path segment.
     * Examples: `User` → `users`, `OrderItem` → `order-items`
     */
    private fun toKebabCasePlural(name: String): String {
        val kebab =
            buildString {
                for ((i, ch) in name.withIndex()) {
                    if (ch.isUpperCase() && i > 0) append('-')
                    append(ch.lowercaseChar())
                }
            }
        return kebab.toKebabPlural()
    }

    private fun String.toKebabPlural(): String =
        when {
            endsWith("y") && length > 1 && this[length - 2].lowercaseChar() !in "aeiou" ->
                dropLast(1) + "ies"
            endsWith("s") ||
                endsWith("x") ||
                endsWith("z") ||
                endsWith("-ch") ||
                endsWith("-sh") -> "${this}es"
            else -> "${this}s"
        }

    /**
     * Simple plural for operationId and summary text (PascalCase class name).
     * Examples: `User` → `Users`, `Category` → `Categories`
     */
    private fun toSimplePlural(name: String): String =
        when {
            name.endsWith("y") && name.length > 1 && name[name.length - 2].lowercaseChar() !in "aeiou" ->
                name.dropLast(1) + "ies"
            name.endsWith("s") || name.endsWith("x") || name.endsWith("z") ->
                "${name}es"
            else -> "${name}s"
        }

    private companion object {
        const val DEFAULT_BASE_PATH = "/api/v1"
        const val DEFAULT_TITLE = "Generated API"
        const val DEFAULT_VERSION = "1.0.0"
        const val RULE_CLASS_TO_PATH = "uml-class-to-openapi-paths"
    }
}

/** ServiceLoader provider for [UmlToRestTransformer]. */
public class UmlToRestTransformerProvider : KumlTransformerProvider {
    override fun transformer(): UmlToRestTransformer = UmlToRestTransformer()
}
