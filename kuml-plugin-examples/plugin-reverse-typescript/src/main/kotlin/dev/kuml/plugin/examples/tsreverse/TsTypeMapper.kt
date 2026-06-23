package dev.kuml.plugin.examples.tsreverse

internal class TsTypeMapper {
    /** Public entry point — delegates to the depth-guarded implementation. */
    fun map(tsType: String): String = mapInternal(tsType, depth = 0)

    private fun mapInternal(
        tsType: String,
        depth: Int,
    ): String {
        if (depth > MAX_RECURSION_DEPTH) return tsType.trim()
        val trimmed = tsType.trim()
        val lower = trimmed.lowercase()
        return when {
            lower == "string" -> "String"
            lower == "number" -> "Double"
            lower == "boolean" -> "Boolean"
            lower == "void" -> "void"
            lower == "any" || lower == "unknown" -> "Object"
            lower == "never" -> "void"
            lower == "null" || lower == "undefined" -> "Object"
            lower == "date" -> "Date"
            lower == "object" -> "Object"
            lower == "function" -> "Function"
            lower == "symbol" -> "Symbol"
            lower == "bigint" -> "Long"
            trimmed.endsWith("[]") -> "List<${mapInternal(trimmed.dropLast(2), depth + 1)}>"
            trimmed.startsWith("Array<") && trimmed.endsWith(">") -> {
                val inner = trimmed.drop("Array<".length).dropLast(1)
                "List<${mapInternal(inner, depth + 1)}>"
            }
            trimmed.startsWith("Promise<") && trimmed.endsWith(">") -> {
                val inner = trimmed.drop("Promise<".length).dropLast(1)
                mapInternal(inner, depth + 1)
            }
            trimmed.startsWith("Record<") -> "Map"
            trimmed.startsWith("Map<") -> "Map"
            trimmed.startsWith("Set<") -> "Set"
            trimmed.startsWith("ReadonlyArray<") && trimmed.endsWith(">") -> {
                val inner = trimmed.drop("ReadonlyArray<".length).dropLast(1)
                "List<${mapInternal(inner, depth + 1)}>"
            }
            trimmed.startsWith("Partial<") ||
                trimmed.startsWith("Required<") ||
                trimmed.startsWith("Readonly<") ||
                trimmed.startsWith("NonNullable<") -> {
                val inner = trimmed.substringAfter("<").dropLast(1)
                mapInternal(inner, depth + 1)
            }
            trimmed.startsWith("Optional<") && trimmed.endsWith(">") -> {
                val inner = trimmed.drop("Optional<".length).dropLast(1)
                mapInternal(inner, depth + 1)
            }
            trimmed.contains(" | ") -> {
                val parts = trimmed.split(" | ").map { it.trim() }
                val nonNull = parts.filter { it != "null" && it != "undefined" }
                if (nonNull.size == 1) mapInternal(nonNull[0], depth + 1) else trimmed
            }
            else -> trimmed
        }
    }

    private companion object {
        /** Maximum nesting depth for recursive type mapping before bailing out. */
        const val MAX_RECURSION_DEPTH: Int = 32
    }
}
