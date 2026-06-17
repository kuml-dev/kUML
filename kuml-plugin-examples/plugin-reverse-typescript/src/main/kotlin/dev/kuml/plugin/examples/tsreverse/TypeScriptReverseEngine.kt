package dev.kuml.plugin.examples.tsreverse

import dev.kuml.codegen.reverse.KumlReverseEngine
import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.ReverseResult
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef

/**
 * Regex-based TypeScript → UML reverse engine (MVP).
 *
 * Detects top-level `interface`, `class`, and `enum` declarations plus their
 * property and method signatures via simple line-by-line pattern matching.
 *
 * ## Limitations vs. full AST parsing (V3.1 via ts-morph)
 * - No generics, decorators, or conditional types
 * - No cross-file import resolution
 * - Nested / anonymous types are skipped
 * - Multi-line property/method bodies may confuse the parser
 *
 * ## CLI usage
 * ```
 * kuml reverse --lang typescript src/
 * ```
 */
public class TypeScriptReverseEngine : KumlReverseEngine {
    override val id: String = "typescript"
    override val description: String =
        "Regex-based TypeScript reverse engine (MVP). Full AST via ts-morph planned for V3.1."

    override suspend fun analyze(request: ReverseRequest): ReverseResult {
        val elements = mutableListOf<KumlElement>()
        var filesAnalysed = 0
        val startMs = System.currentTimeMillis()

        for (root in request.sourceRoots) {
            root
                .toFile()
                .walkTopDown()
                .filter { it.isFile && it.extension == "ts" }
                .forEach { file ->
                    elements += parseTypeScriptFile(file.readText(), file.nameWithoutExtension)
                    filesAnalysed++
                }
        }

        val diagram =
            KumlDiagram(
                id = request.targetModelName,
                name = request.targetModelName,
                type = DiagramType.CLASS,
                elements = elements,
            )
        val model =
            KumlModel(
                root = diagram,
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = request.targetModelName,
            )
        return ReverseResult.Success(
            model = model,
            filesAnalysed = filesAnalysed,
            elapsedMs = System.currentTimeMillis() - startMs,
        )
    }

    internal fun parseTypeScriptFile(
        source: String,
        fileHint: String,
    ): List<KumlElement> {
        val elements = mutableListOf<KumlElement>()

        // export interface IFoo { ... }
        INTERFACE_RE.findAll(source).forEach { m ->
            val name = m.groupValues[1]
            val body = m.groupValues[2]
            elements +=
                UmlInterface(
                    id = "ts::$name",
                    name = name,
                    attributes = parseProperties(body, name),
                    operations = parseMethods(body, name),
                )
        }

        // export [abstract] class Foo { ... }
        CLASS_RE.findAll(source).forEach { m ->
            val name = m.groupValues[1]
            val body = m.groupValues[2]
            val isAbstract = ABSTRACT_RE.containsMatchIn(source.substringBefore("{"))
            elements +=
                UmlClass(
                    id = "ts::$name",
                    name = name,
                    isAbstract = isAbstract,
                    attributes = parseProperties(body, name),
                    operations = parseMethods(body, name),
                )
        }

        // export enum Foo { A, B, C }
        ENUM_RE.findAll(source).forEach { m ->
            val name = m.groupValues[1]
            val literals =
                m.groupValues[2]
                    .split(",")
                    .map { it.trim().substringBefore("=").trim() }
                    .filter { it.isNotBlank() && it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
                    .mapIndexed { i, lit ->
                        UmlEnumerationLiteral(id = "ts::$name::$lit", name = lit)
                    }
            elements += UmlEnumeration(id = "ts::$name", name = name, literals = literals)
        }

        return elements
    }

    private fun parseProperties(
        body: String,
        owner: String,
    ): List<UmlProperty> =
        PROP_RE
            .findAll(body)
            .map { m ->
                val propName = m.groupValues[1]
                val tsType = m.groupValues[2]
                UmlProperty(
                    id = "ts::$owner::$propName",
                    name = propName,
                    type = UmlTypeRef(name = mapTsType(tsType)),
                )
            }.toList()

    private fun parseMethods(
        body: String,
        owner: String,
    ): List<UmlOperation> =
        METHOD_RE
            .findAll(body)
            .map { m ->
                val methodName = m.groupValues[1]
                val returnTs = m.groupValues[2]
                UmlOperation(
                    id = "ts::$owner::$methodName()",
                    name = methodName,
                    returnType = UmlTypeRef(name = mapTsType(returnTs)),
                )
            }.toList()

    internal fun mapTsType(tsType: String): String =
        when (tsType.lowercase().trim()) {
            "string" -> "String"
            "number" -> "Double"
            "boolean" -> "Boolean"
            "void" -> "void"
            "any" -> "Object"
            "date" -> "Date"
            else -> tsType.trim()
        }

    private companion object {
        val INTERFACE_RE =
            Regex(
                """(?:export\s+)?interface\s+(\w+)[^{]*\{([^}]*)}""",
                RegexOption.DOT_MATCHES_ALL,
            )
        val CLASS_RE =
            Regex(
                """(?:export\s+)?(?:abstract\s+)?class\s+(\w+)[^{]*\{([^}]*)}""",
                RegexOption.DOT_MATCHES_ALL,
            )
        val ABSTRACT_RE = Regex("""abstract\s+class""")
        val ENUM_RE = Regex("""(?:export\s+)?enum\s+(\w+)\s*\{([^}]*)}""")
        val PROP_RE = Regex("""^\s*(\w+)\??:\s*(\w+)""", RegexOption.MULTILINE)
        val METHOD_RE = Regex("""^\s*(\w+)\s*\([^)]*\)\s*:\s*(\w+)""", RegexOption.MULTILINE)
    }
}
