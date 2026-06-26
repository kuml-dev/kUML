package dev.kuml.plugin.examples.csharp

/**
 * Maps kUML / UML primitive type names to C# types.
 *
 * Type names are NEVER passed through the naming convention — C# types are lowercase
 * keywords (string, int, bool) or fully qualified names (System.Guid) regardless of
 * any [CsharpNamingConvention] setting.
 */
public class CsharpTypeMapper(
    @Suppress("UNUSED_PARAMETER")
    private val naming: CsharpNamingConvention = CsharpNamingConvention.DEFAULT,
) {
    /**
     * Maps a kUML type name to a C# type name.
     *
     * Unknown types are passed through unchanged (user-defined types stay PascalCase).
     */
    public fun mapType(kumlType: String): String =
        when (kumlType.lowercase()) {
            "string" -> "string"
            "int", "integer" -> "int"
            "long" -> "long"
            "short" -> "short"
            "byte" -> "byte"
            "float" -> "float"
            "double", "bigdecimal" -> "double"
            "boolean", "bool" -> "bool"
            "char" -> "char"
            "void" -> "void"
            "uuid" -> "System.Guid"
            "date", "localdate", "localdatetime", "instant" -> "System.DateTime"
            "list", "collection", "iterable" -> "System.Collections.Generic.List<object>"
            "set" -> "System.Collections.Generic.HashSet<object>"
            "map" -> "System.Collections.Generic.Dictionary<string, object>"
            else -> kumlType
        }

    /**
     * Returns `true` if the given C# type is a value type keyword.
     *
     * Value types must NOT receive the NRT `?` suffix — `int?` is `Nullable<int>`,
     * which has different semantics from a nullable reference type annotation.
     * Use `Nullable<T>` form or simply omit the suffix and let the caller decide.
     */
    public fun isValueType(csType: String): Boolean = csType in VALUE_TYPES

    public companion object {
        private val VALUE_TYPES =
            setOf("int", "long", "short", "byte", "float", "double", "bool", "char", "void")
    }

    /**
     * Returns sorted `using` directives needed for the given C# type names.
     *
     * @param typeNames C# type names actually used (output of [mapType]).
     */
    public fun usingsFor(typeNames: Collection<String>): List<String> {
        val usings = mutableSetOf<String>()
        for (t in typeNames) {
            if (t.startsWith("System.Collections.Generic")) {
                usings += "using System.Collections.Generic;"
                usings += "using System;"
            } else if (t.startsWith("System.")) {
                usings += "using System;"
            }
        }
        return usings.sorted()
    }
}
