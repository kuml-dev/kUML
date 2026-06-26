package dev.kuml.plugin.examples.cppreverse

/**
 * Maps C++ type names to UML-friendly type names.
 *
 * Handles primitives, STL containers, smart pointers, and qualified names.
 * Unknown types pass through trimmed.
 *
 * Depth-guarded to avoid infinite recursion on pathological input.
 */
internal class CppTypeMapper {
    /** Public entry point — delegates to the depth-guarded implementation. */
    fun map(cppType: String): String = mapInternal(cppType.trim(), depth = 0)

    @Suppress("CyclomaticComplexMethod")
    private fun mapInternal(
        t: String,
        depth: Int,
    ): String {
        if (depth > MAX_RECURSION_DEPTH) return t
        val trimmed = t.trim()
        val noConst = trimmed.removePrefix("const ").removeSuffix(" const").trim()
        val noPtr = noConst.trimEnd('*', '&', ' ').trim()

        return when {
            noPtr == "bool" -> "Boolean"
            noPtr == "int" ||
                noPtr == "short" ||
                noPtr == "int16_t" ||
                noPtr == "int32_t" ||
                noPtr == "uint16_t" ||
                noPtr == "uint32_t" -> "Int"
            noPtr == "long" ||
                noPtr == "long long" ||
                noPtr == "size_t" ||
                noPtr == "ptrdiff_t" ||
                noPtr == "int64_t" ||
                noPtr == "uint64_t" ||
                noPtr == "int8_t" ||
                noPtr == "uint8_t" -> "Long"
            noPtr.startsWith("unsigned ") -> mapInternal(noPtr.removePrefix("unsigned ").trim(), depth + 1)
            noPtr.startsWith("signed ") -> mapInternal(noPtr.removePrefix("signed ").trim(), depth + 1)
            noPtr == "float" || noPtr == "double" || noPtr == "long double" -> "Double"
            noPtr == "char" || noPtr == "wchar_t" || noPtr == "char16_t" || noPtr == "char32_t" -> "Char"
            noPtr == "void" -> "void"
            noPtr == "string" ||
                noPtr == "std::string" ||
                noPtr == "wstring" ||
                noPtr == "std::wstring" -> "String"
            noPtr.startsWith("std::vector<") && noPtr.endsWith(">") -> {
                val inner = noPtr.drop("std::vector<".length).dropLast(1)
                "List<${mapInternal(inner, depth + 1)}>"
            }
            noPtr.startsWith("vector<") && noPtr.endsWith(">") -> {
                val inner = noPtr.drop("vector<".length).dropLast(1)
                "List<${mapInternal(inner, depth + 1)}>"
            }
            noPtr.startsWith("std::list<") && noPtr.endsWith(">") -> {
                val inner = noPtr.drop("std::list<".length).dropLast(1)
                "List<${mapInternal(inner, depth + 1)}>"
            }
            noPtr.startsWith("list<") && noPtr.endsWith(">") -> {
                val inner = noPtr.drop("list<".length).dropLast(1)
                "List<${mapInternal(inner, depth + 1)}>"
            }
            noPtr.startsWith("std::map<") && noPtr.endsWith(">") -> "Map"
            noPtr.startsWith("map<") && noPtr.endsWith(">") -> "Map"
            noPtr.startsWith("std::unordered_map<") && noPtr.endsWith(">") -> "Map"
            noPtr.startsWith("unordered_map<") && noPtr.endsWith(">") -> "Map"
            noPtr.startsWith("std::set<") && noPtr.endsWith(">") -> "Set"
            noPtr.startsWith("set<") && noPtr.endsWith(">") -> "Set"
            noPtr.startsWith("std::unordered_set<") && noPtr.endsWith(">") -> "Set"
            noPtr.startsWith("std::shared_ptr<") && noPtr.endsWith(">") -> {
                val inner = noPtr.drop("std::shared_ptr<".length).dropLast(1)
                mapInternal(inner, depth + 1)
            }
            noPtr.startsWith("shared_ptr<") && noPtr.endsWith(">") -> {
                val inner = noPtr.drop("shared_ptr<".length).dropLast(1)
                mapInternal(inner, depth + 1)
            }
            noPtr.startsWith("std::unique_ptr<") && noPtr.endsWith(">") -> {
                val inner = noPtr.drop("std::unique_ptr<".length).dropLast(1)
                mapInternal(inner, depth + 1)
            }
            noPtr.startsWith("unique_ptr<") && noPtr.endsWith(">") -> {
                val inner = noPtr.drop("unique_ptr<".length).dropLast(1)
                mapInternal(inner, depth + 1)
            }
            noPtr.startsWith("std::weak_ptr<") && noPtr.endsWith(">") -> {
                val inner = noPtr.drop("std::weak_ptr<".length).dropLast(1)
                mapInternal(inner, depth + 1)
            }
            noPtr.startsWith("weak_ptr<") && noPtr.endsWith(">") -> {
                val inner = noPtr.drop("weak_ptr<".length).dropLast(1)
                mapInternal(inner, depth + 1)
            }
            else -> noPtr.ifEmpty { trimmed }
        }
    }

    private companion object {
        const val MAX_RECURSION_DEPTH: Int = 32
    }
}
