package dev.kuml.plugin.examples.csharp

import dev.kuml.codegen.api.KumlCodeGenerator
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlProperty
import java.io.File

/**
 * Generates C# source file (`.cs`) skeletons from UML class diagrams.
 *
 * - [UmlClass] → `<Name>.cs` (class or `abstract class`)
 * - [UmlInterface] → `I<Name>.cs` (interface, prefixed with `I` if not already so)
 * - [UmlEnumeration] → `<Name>.cs` (`enum`)
 */
public class CsharpCodeGenerator : KumlCodeGenerator {
    override val id: String = "csharp"
    override val displayName: String = "C#"

    override fun generate(
        diagram: KumlDiagram,
        outputDir: File,
        options: Map<String, String>,
    ): List<File> {
        val opts = CsharpGeneratorOptions.from(options)
        outputDir.mkdirs()

        // Flatten all named elements, collecting package namespace along the way.
        // elementNamespace[elementId] = namespace derived from owning UmlPackage (dotted).
        val elementNamespace = mutableMapOf<String, String>()
        val umlNamedElements = diagram.elements.filterIsInstance<UmlNamedElement>()
        val flatElements = flattenElements(umlNamedElements, namespace = null, elementNamespace)

        // Pre-build index: element id → display name (possibly I-prefixed for interfaces)
        val idToName = mutableMapOf<String, String>()
        for (el in flatElements) {
            when (el) {
                is UmlClass -> idToName[el.id] = el.name
                is UmlInterface -> idToName[el.id] = interfaceName(el.name)
                is UmlEnumeration -> idToName[el.id] = el.name
                else -> {}
            }
        }

        // Pre-collect generalizations: specificId → generalId (UmlRelationship, not UmlNamedElement)
        val generalizations = mutableMapOf<String, String>()
        for (el in diagram.elements) {
            if (el is UmlGeneralization) {
                generalizations[el.specificId] = el.generalId
            }
        }

        // Pre-collect interface realizations: implementingId → list of interfaceId
        val realizations = mutableMapOf<String, MutableList<String>>()
        for (el in diagram.elements) {
            if (el is UmlInterfaceRealization) {
                realizations.getOrPut(el.implementingId) { mutableListOf() } += el.interfaceId
            }
        }

        val generated = mutableListOf<File>()
        for (el in flatElements) {
            // Effective namespace: package-derived namespace takes precedence over global option.
            val effectiveNs = elementNamespace[el.id] ?: opts.namespaceName
            val effectiveOpts = opts.copy(namespaceName = effectiveNs)

            when (el) {
                is UmlClass -> {
                    val content = generateClass(el, generalizations, realizations, idToName, effectiveOpts)
                    val safeName = sanitizeElementName(el.name)
                    val file = File(outputDir, "$safeName.cs")
                    file.writeText(content)
                    generated += file
                }
                is UmlInterface -> {
                    val content = generateInterface(el, effectiveOpts)
                    val safeName = sanitizeElementName(interfaceName(el.name))
                    val file = File(outputDir, "$safeName.cs")
                    file.writeText(content)
                    generated += file
                }
                is UmlEnumeration -> {
                    val content = generateEnum(el, effectiveOpts)
                    val safeName = sanitizeElementName(el.name)
                    val file = File(outputDir, "$safeName.cs")
                    file.writeText(content)
                    generated += file
                }
                else -> {}
            }
        }
        return generated
    }

    /**
     * Returns the C# interface name for a UML interface name.
     *
     * Prefixes with `I` if the name does not already start with `I` followed by an uppercase letter.
     * Examples: `Orderable` → `IOrderable`, `IRepo` → `IRepo`, `item` → `Iitem`.
     */
    private fun interfaceName(name: String): String =
        if (name.length >= 2 && name[0] == 'I' && name[1].isUpperCase()) {
            name
        } else {
            "I$name"
        }

    /**
     * Sanitizes a UML element name for use as a file name component.
     *
     * Replaces every character that is not a letter, digit, or underscore with `_`.
     * This eliminates path separators and traversal sequences.
     */
    private fun sanitizeElementName(name: String): String {
        val sanitized = name.replace(Regex("[^A-Za-z0-9_]"), "_")
        return sanitized.ifBlank { "_" }
    }

    /**
     * Recursively flattens [elements], descending into [UmlPackage.members].
     * For each element inside a package the owning package name is accumulated
     * into a `.`-separated namespace string and stored in [elementNamespace].
     */
    private fun flattenElements(
        elements: List<UmlNamedElement>,
        namespace: String?,
        elementNamespace: MutableMap<String, String>,
    ): List<UmlNamedElement> {
        val result = mutableListOf<UmlNamedElement>()
        for (el in elements) {
            when (el) {
                is UmlPackage -> {
                    val childNs = if (namespace != null) "$namespace.${el.name}" else el.name
                    result += flattenElements(el.members, childNs, elementNamespace)
                }
                else -> {
                    if (namespace != null) {
                        elementNamespace[el.id] = namespace
                    }
                    result += el
                }
            }
        }
        return result
    }

    private fun generateClass(
        cls: UmlClass,
        generalizations: Map<String, String>,
        realizations: Map<String, List<String>>,
        idToName: Map<String, String>,
        opts: CsharpGeneratorOptions,
    ): String {
        val mapper = CsharpTypeMapper(opts.naming)

        // Collect all C# types used in properties for 'using' directives
        val propTypes = cls.attributes.map { mapper.mapType(it.type.name) }
        val returnTypes = cls.operations.mapNotNull { it.returnType?.let { r -> mapper.mapType(r.name) } }
        val paramTypes = cls.operations.flatMap { op -> op.parameters.map { mapper.mapType(it.type.name) } }
        val allTypes = propTypes + returnTypes + paramTypes

        val sb = StringBuilder()
        sb.appendLine("// Generated by kUML C# Code Generator")

        if (opts.useNullableReferenceTypes) {
            sb.appendLine("#nullable enable")
            sb.appendLine()
        }

        if (opts.generateUsings) {
            val usings = mapper.usingsFor(allTypes)
            if (usings.isNotEmpty()) {
                for (u in usings) {
                    sb.appendLine(u)
                }
                sb.appendLine()
            }
        }

        val indent = openNamespace(sb, opts.namespaceName)

        val baseList = buildBaseList(cls.id, generalizations, realizations, idToName)
        val abstractModifier = if (cls.isAbstract) "abstract " else ""
        sb.appendLine("${indent}public ${abstractModifier}class ${cls.name}$baseList")
        sb.appendLine("$indent{")

        for (prop in cls.attributes) {
            sb.appendLine("$indent    ${formatProperty(prop, mapper, opts)}")
        }

        for (op in cls.operations) {
            sb.appendLine("$indent    ${formatOperation(op, mapper, opts, isInterface = false)}")
        }

        sb.appendLine("$indent}")

        closeNamespace(sb, opts.namespaceName)

        return sb.toString()
    }

    private fun generateInterface(
        iface: UmlInterface,
        opts: CsharpGeneratorOptions,
    ): String {
        val mapper = CsharpTypeMapper(opts.naming)

        val allTypes =
            iface.attributes.map { mapper.mapType(it.type.name) } +
                iface.operations.mapNotNull { it.returnType?.let { r -> mapper.mapType(r.name) } } +
                iface.operations.flatMap { op -> op.parameters.map { mapper.mapType(it.type.name) } }

        val sb = StringBuilder()
        sb.appendLine("// Generated by kUML C# Code Generator")

        if (opts.useNullableReferenceTypes) {
            sb.appendLine("#nullable enable")
            sb.appendLine()
        }

        if (opts.generateUsings) {
            val usings = mapper.usingsFor(allTypes)
            if (usings.isNotEmpty()) {
                for (u in usings) {
                    sb.appendLine(u)
                }
                sb.appendLine()
            }
        }

        val iName = interfaceName(iface.name)
        val indent = openNamespace(sb, opts.namespaceName)

        sb.appendLine("${indent}public interface $iName")
        sb.appendLine("$indent{")

        for (prop in iface.attributes) {
            sb.appendLine("$indent    ${formatProperty(prop, mapper, opts)}")
        }

        for (op in iface.operations) {
            sb.appendLine("$indent    ${formatOperation(op, mapper, opts, isInterface = true)}")
        }

        sb.appendLine("$indent}")

        closeNamespace(sb, opts.namespaceName)

        return sb.toString()
    }

    private fun generateEnum(
        enum: UmlEnumeration,
        opts: CsharpGeneratorOptions,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("// Generated by kUML C# Code Generator")
        sb.appendLine()

        val indent = openNamespace(sb, opts.namespaceName)

        sb.appendLine("${indent}public enum ${enum.name}")
        sb.appendLine("$indent{")

        enum.literals.forEachIndexed { i, lit ->
            val comma = if (i < enum.literals.size - 1) "," else ""
            sb.appendLine("$indent    ${lit.name}$comma")
        }

        sb.appendLine("$indent}")

        closeNamespace(sb, opts.namespaceName)

        return sb.toString()
    }

    /**
     * Builds the base-list string for a class (`: Base, IFoo, IBar`).
     *
     * C# requires the base class to precede interfaces. Returns empty string when no
     * generalization or realizations exist.
     */
    private fun buildBaseList(
        id: String,
        generalizations: Map<String, String>,
        realizations: Map<String, List<String>>,
        idToName: Map<String, String>,
    ): String {
        val parts = mutableListOf<String>()

        // Base class first
        val baseId = generalizations[id]
        if (baseId != null) {
            val baseName = idToName[baseId]
            if (baseName != null) {
                parts += baseName
            }
        }

        // Interfaces next (in order of realization)
        val ifaceIds = realizations[id] ?: emptyList()
        for (ifaceId in ifaceIds) {
            val ifaceName = idToName[ifaceId]
            if (ifaceName != null) {
                parts += ifaceName
            }
        }

        return if (parts.isEmpty()) "" else " : ${parts.joinToString(", ")}"
    }

    private fun formatProperty(
        prop: UmlProperty,
        mapper: CsharpTypeMapper,
        opts: CsharpGeneratorOptions,
    ): String {
        val csType = mapper.mapType(prop.type.name)
        // Value types (int, bool, long, …) must NOT receive the NRT '?' suffix —
        // 'int?' is Nullable<int> and has different semantics from an NRT annotation.
        // Only reference types are eligible for the '?' suffix under #nullable enable.
        val nullable = opts.useNullableReferenceTypes && prop.multiplicity.lower == 0 && !mapper.isValueType(csType)
        val typeStr = if (nullable) "$csType?" else csType
        val propName = opts.naming.apply(prop.name)
        return "public $typeStr $propName { get; set; }"
    }

    private fun formatOperation(
        op: UmlOperation,
        mapper: CsharpTypeMapper,
        opts: CsharpGeneratorOptions,
        isInterface: Boolean,
    ): String {
        val returnType = op.returnType?.let { mapper.mapType(it.name) } ?: "void"
        val params =
            op.parameters.joinToString(", ") { p ->
                "${mapper.mapType(p.type.name)} ${opts.naming.apply(p.name)}"
            }
        val methodName = opts.naming.apply(op.name)
        return if (isInterface) {
            "$returnType $methodName($params);"
        } else {
            "public $returnType $methodName($params) { }"
        }
    }

    /**
     * Opens a namespace block. Returns the indentation string to use for members inside.
     */
    private fun openNamespace(
        sb: StringBuilder,
        namespaceName: String?,
    ): String {
        if (namespaceName == null) return ""
        sb.appendLine("namespace $namespaceName")
        sb.appendLine("{")
        return "    "
    }

    private fun closeNamespace(
        sb: StringBuilder,
        namespaceName: String?,
    ) {
        if (namespaceName == null) return
        sb.appendLine("}")
    }
}
