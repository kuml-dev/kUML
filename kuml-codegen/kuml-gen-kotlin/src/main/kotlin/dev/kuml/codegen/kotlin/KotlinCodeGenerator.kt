package dev.kuml.codegen.kotlin

import dev.kuml.codegen.api.KumlCodeGenerator
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.Visibility
import java.io.File

/**
 * Built-in Kotlin code generator for kUML class diagrams.
 *
 * Generates idiomatic Kotlin source files from UML classifiers:
 * - [UmlClass] → `data class` / `class` / `abstract class`
 * - [UmlInterface] → `interface`
 * - [UmlEnumeration] → `enum class`
 *
 * Relationships:
 * - [UmlGeneralization] → `: SuperClass()` / `: SuperInterface`
 * - [UmlInterfaceRealization] → `: Interface`
 *
 * Multiplicity:
 * - `(1,1)` → `TypeName`
 * - `(0,1)` → `TypeName?`
 * - `(0,*)` or `(1,*)` → `List<TypeName>`
 */
public class KotlinCodeGenerator : KumlCodeGenerator {
    override val id: String = "kotlin"
    override val displayName: String = "Kotlin (Data Classes)"

    override fun generate(
        diagram: KumlDiagram,
        outputDir: File,
        options: Map<String, String>,
    ): List<File> {
        val packageName = options["package"]?.takeIf { it.isNotBlank() }
        outputDir.mkdirs()

        // Build index: id → element (for inheritance resolution)
        val elementById = diagram.elements.associateBy { it.id }

        // Collect relationships
        val generalizations = diagram.elements.filterIsInstance<UmlGeneralization>()
        val realizations = diagram.elements.filterIsInstance<UmlInterfaceRealization>()

        // superTypes[classId] = list of parent type names (classes or interfaces)
        val classParents: MutableMap<String, MutableList<String>> = mutableMapOf()
        for (gen in generalizations) {
            val parentEl = elementById[gen.generalId]
            val parentName =
                when (parentEl) {
                    is UmlClass -> parentEl.name + "()"
                    is UmlInterface -> parentEl.name
                    else -> continue
                }
            classParents.getOrPut(gen.specificId) { mutableListOf() }.add(parentName)
        }
        for (real in realizations) {
            val iface = elementById[real.interfaceId] as? UmlInterface ?: continue
            classParents.getOrPut(real.implementingId) { mutableListOf() }.add(iface.name)
        }

        val written = mutableListOf<File>()

        for (element in diagram.elements) {
            val (name, code) =
                when (element) {
                    is UmlClass -> element.name to generateClass(element, packageName, classParents[element.id] ?: emptyList())
                    is UmlInterface -> element.name to generateInterface(element, packageName, classParents[element.id] ?: emptyList())
                    is UmlEnumeration -> element.name to generateEnum(element, packageName)
                    else -> continue
                }
            val file = outputDir.resolve("${name.capitalizeFirst()}.kt")
            file.writeText(code)
            written += file
        }

        return written
    }

    private fun generateClass(
        cls: UmlClass,
        packageName: String?,
        parents: List<String>,
    ): String {
        val sb = StringBuilder()
        sb.append(fileHeader(packageName))

        val isDataClass = !cls.isAbstract && cls.attributes.isNotEmpty() && cls.operations.isEmpty()
        val classKeyword =
            when {
                cls.isAbstract -> "abstract class"
                isDataClass -> "data class"
                else -> "class"
            }

        val visStr = cls.visibility.toKotlin()

        // Collect imports
        val imports = mutableSetOf<String>()
        cls.attributes.forEach { prop ->
            KotlinImportCollector.collectForType(prop.type.name)?.let { imports += it }
        }
        cls.operations.forEach { op ->
            op.returnType?.let { KotlinImportCollector.collectForType(it.name)?.let { imp -> imports += imp } }
            op.parameters.forEach { param ->
                KotlinImportCollector.collectForType(param.type.name)?.let { imports += it }
            }
        }
        if (imports.isNotEmpty()) {
            imports.sorted().forEach { sb.appendLine("import $it") }
            sb.appendLine()
        }

        // Class declaration
        if (isDataClass) {
            sb.append("$visStr $classKeyword ${cls.name}(")
            val ctorParams =
                cls.attributes.joinToString(",\n    ") { prop ->
                    val type = KotlinTypeMapper.toKotlinType(prop.type, prop.multiplicity)
                    "    val ${prop.name}: $type" + (if (prop.defaultValue != null) " = ${prop.defaultValue}" else "")
                }
            if (cls.attributes.size > 1) {
                sb.appendLine("\n$ctorParams,")
                sb.append(")")
            } else if (cls.attributes.size == 1) {
                sb.append("\n$ctorParams\n)")
            }
        } else {
            sb.append("$visStr $classKeyword ${cls.name}")
            if (cls.attributes.isNotEmpty()) {
                sb.append("(\n")
                cls.attributes.forEach { prop ->
                    val valOrVar = if (prop.isReadOnly) "val" else "var"
                    val type = KotlinTypeMapper.toKotlinType(prop.type, prop.multiplicity)
                    sb.appendLine("    $valOrVar ${prop.name}: $type,")
                }
                sb.append(")")
            }
        }

        // Inheritance
        if (parents.isNotEmpty()) {
            sb.append(" : ${parents.joinToString(", ")}")
        }

        // Body
        if (cls.operations.isNotEmpty() || (!isDataClass && cls.attributes.isEmpty())) {
            sb.appendLine(" {")
            cls.operations.forEach { op -> sb.append(generateOperation(op, "    ")) }
            sb.appendLine("}")
        } else {
            sb.appendLine()
        }

        return sb.toString()
    }

    private fun generateInterface(
        iface: UmlInterface,
        packageName: String?,
        parents: List<String>,
    ): String {
        val sb = StringBuilder()
        sb.append(fileHeader(packageName))

        val visStr = iface.visibility.toKotlin()

        sb.append("$visStr interface ${iface.name}")
        if (parents.isNotEmpty()) sb.append(" : ${parents.joinToString(", ")}")
        sb.appendLine(" {")

        iface.operations.forEach { op ->
            val returnType = KotlinTypeMapper.toKotlinReturnType(op.returnType)
            val params =
                op.parameters.joinToString(", ") { p ->
                    "${p.name}: ${KotlinTypeMapper.toKotlinType(p.type, Multiplicity())}"
                }
            sb.appendLine("    fun ${op.name}($params): $returnType")
        }

        sb.appendLine("}")
        return sb.toString()
    }

    private fun generateEnum(
        enum: UmlEnumeration,
        packageName: String?,
    ): String {
        val sb = StringBuilder()
        sb.append(fileHeader(packageName))
        sb.appendLine("enum class ${enum.name} {")
        enum.literals.forEach { lit ->
            sb.appendLine("    ${lit.name.uppercase()},")
        }
        sb.appendLine("}")
        return sb.toString()
    }

    private fun generateOperation(
        op: UmlOperation,
        indent: String,
    ): String {
        val sb = StringBuilder()
        val visStr = op.visibility.toKotlin()
        val returnType = KotlinTypeMapper.toKotlinReturnType(op.returnType)
        val params =
            op.parameters.joinToString(", ") { p ->
                "${p.name}: ${KotlinTypeMapper.toKotlinType(p.type, Multiplicity())}"
            }
        val abstractMod = if (op.isAbstract) "abstract " else ""

        sb.append("$indent$visStr ${abstractMod}fun ${op.name}($params): $returnType")
        if (op.isAbstract) {
            sb.appendLine()
        } else {
            sb.appendLine(" {")
            sb.appendLine("$indent    TODO(\"Not yet implemented\")")
            sb.appendLine("$indent}")
        }
        return sb.toString()
    }

    private fun fileHeader(packageName: String?): String =
        buildString {
            appendLine("// Generated by kuml-gen-kotlin — do not edit manually.")
            appendLine()
            if (packageName != null) {
                appendLine("package $packageName")
                appendLine()
            }
        }

    private fun String.capitalizeFirst(): String = replaceFirstChar { it.uppercase() }

    private fun Visibility.toKotlin(): String =
        when (this) {
            Visibility.PUBLIC -> "public"
            Visibility.PROTECTED -> "protected"
            Visibility.PRIVATE -> "private"
            Visibility.PACKAGE -> "internal"
        }
}
