package dev.kuml.plugin.examples.cpp

/**
 * Maps kUML / UML primitive type names to C++ types.
 *
 * Type names are NEVER passed through the naming convention — C++ types are PascalCase
 * (or standard library names) regardless of any [CppNamingConvention] setting.
 */
public class CppTypeMapper(
    private val naming: CppNamingConvention = CppNamingConvention.DEFAULT,
) {
    /**
     * Maps a kUML type name to a C++ type name.
     *
     * Unknown types are passed through unchanged (user-defined types stay PascalCase).
     */
    internal fun mapType(kumlType: String): String =
        when (kumlType.lowercase()) {
            "string" -> "std::string"
            "int", "integer" -> "int"
            "long" -> "long"
            "short" -> "short"
            "byte" -> "char"
            "float" -> "float"
            "double", "bigdecimal" -> "double"
            "boolean", "bool" -> "bool"
            "char" -> "char"
            "void" -> "void"
            "uuid" -> "std::string"
            "date", "localdate", "localdatetime", "instant" -> "std::string"
            "list", "collection", "iterable", "set" -> "std::vector<void*>"
            "map" -> "std::map<std::string, void*>"
            else -> kumlType
        }

    /**
     * Returns the set of `#include` directives needed for the given C++ type names.
     *
     * @param typeNames C++ type names actually used (output of [mapType]).
     * @param useSmartPointers Whether smart pointer types are in use (adds `<memory>`).
     * @param hasVectorMembers Whether any `std::vector` member is present (adds `<vector>`).
     */
    internal fun headerIncludesFor(
        typeNames: Collection<String>,
        useSmartPointers: Boolean = false,
        hasVectorMembers: Boolean = false,
    ): List<String> {
        val includes = mutableSetOf<String>()
        for (t in typeNames) {
            if (t.contains("std::string")) includes += "<string>"
            if (t.contains("std::vector")) includes += "<vector>"
            if (t.contains("std::map")) {
                includes += "<map>"
                includes += "<string>"
            }
        }
        if (useSmartPointers) includes += "<memory>"
        if (hasVectorMembers) includes += "<vector>"
        return includes.sorted()
    }
}
