package dev.kuml.plugin.examples.csharpreverse

/**
 * Maps C# type names to UML-friendly type names.
 *
 * Handles C# primitives, BCL aliases, and common generic collection types.
 * Unknown types pass through trimmed.
 *
 * Depth-guarded to avoid infinite recursion on pathological input.
 */
internal class CsharpTypeMapper {
    /** Public entry point — delegates to the depth-guarded implementation. */
    fun map(csType: String): String = mapInternal(csType.trim(), depth = 0)

    @Suppress("CyclomaticComplexMethod")
    private fun mapInternal(
        t: String,
        depth: Int,
    ): String {
        if (depth > MAX_RECURSION_DEPTH) return t
        val trimmed = t.trim()
        // Strip trailing '?' (nullable)
        val noNullable = if (trimmed.endsWith("?")) trimmed.dropLast(1).trim() else trimmed

        return when {
            noNullable == "string" || noNullable == "String" || noNullable == "System.String" -> "String"
            noNullable == "int" ||
                noNullable == "Int32" ||
                noNullable == "System.Int32" ||
                noNullable == "short" ||
                noNullable == "Int16" ||
                noNullable == "byte" ||
                noNullable == "Byte" ||
                noNullable == "uint" ||
                noNullable == "UInt32" ||
                noNullable == "ushort" ||
                noNullable == "UInt16" ||
                noNullable == "sbyte" ||
                noNullable == "SByte" -> "Int"
            noNullable == "long" ||
                noNullable == "Int64" ||
                noNullable == "System.Int64" ||
                noNullable == "ulong" ||
                noNullable == "UInt64" -> "Long"
            noNullable == "bool" || noNullable == "Boolean" || noNullable == "System.Boolean" -> "Boolean"
            noNullable == "float" ||
                noNullable == "Single" ||
                noNullable == "double" ||
                noNullable == "Double" ||
                noNullable == "decimal" ||
                noNullable == "Decimal" -> "Double"
            noNullable == "char" || noNullable == "Char" -> "Char"
            noNullable == "void" -> "void"
            noNullable == "object" || noNullable == "Object" || noNullable == "System.Object" -> "Any"
            // Nullable<T>
            noNullable.startsWith("Nullable<") && noNullable.endsWith(">") -> {
                val inner = noNullable.drop("Nullable<".length).dropLast(1)
                mapInternal(inner, depth + 1)
            }
            // Task<T> → mapped return type
            noNullable.startsWith("Task<") && noNullable.endsWith(">") -> {
                val inner = noNullable.drop("Task<".length).dropLast(1)
                mapInternal(inner, depth + 1)
            }
            noNullable == "Task" -> "void"
            // List<T>, IList<T>, IReadOnlyList<T>
            (
                noNullable.startsWith("List<") ||
                    noNullable.startsWith("IList<") ||
                    noNullable.startsWith("IReadOnlyList<") ||
                    noNullable.startsWith("IEnumerable<") ||
                    noNullable.startsWith("ICollection<") ||
                    noNullable.startsWith("IReadOnlyCollection<")
            ) &&
                noNullable.endsWith(">") -> {
                val inner = noNullable.drop(noNullable.indexOf('<') + 1).dropLast(1)
                "List<${mapInternal(inner, depth + 1)}>"
            }
            // T[] → List<T>
            noNullable.endsWith("[]") -> {
                val inner = noNullable.dropLast(2)
                "List<${mapInternal(inner, depth + 1)}>"
            }
            // Dictionary<K,V>, IDictionary<K,V>
            (
                noNullable.startsWith("Dictionary<") ||
                    noNullable.startsWith("IDictionary<") ||
                    noNullable.startsWith("IReadOnlyDictionary<")
            ) &&
                noNullable.endsWith(">") -> "Map"
            // HashSet<T>, ISet<T>
            (
                noNullable.startsWith("HashSet<") ||
                    noNullable.startsWith("ISet<") ||
                    noNullable.startsWith("SortedSet<")
            ) &&
                noNullable.endsWith(">") -> {
                val inner = noNullable.drop(noNullable.indexOf('<') + 1).dropLast(1)
                "Set<${mapInternal(inner, depth + 1)}>"
            }
            else -> noNullable.ifEmpty { trimmed }
        }
    }

    private companion object {
        const val MAX_RECURSION_DEPTH: Int = 32
    }
}
