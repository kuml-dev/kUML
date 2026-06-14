package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves qualified type names against the symbol solver and caches the outcome.
 *
 * Cache lifetime = one [dev.kuml.codegen.reverse.java.JavaSourceReverseEngine.analyze] call.
 * No global cache to avoid false hits between runs with different source roots.
 */
internal class JavaTypeResolver(
    private val combinedTypeSolver: CombinedTypeSolver,
    /** Fully-qualified names of all user-defined types found in the source roots. */
    private val userTypeNames: Set<String>,
) {
    private val cache = ConcurrentHashMap<String, ResolvedTypeKind>()

    /** Canonical primitive type names used in kUML's UmlTypeRef. */
    private val primitiveMap =
        mapOf(
            "int" to "Int",
            "long" to "Long",
            "boolean" to "Boolean",
            "double" to "Double",
            "float" to "Float",
            "short" to "Short",
            "byte" to "Byte",
            "char" to "Char",
            "void" to "void",
        )

    fun resolveOrExternal(qualifiedName: String): ResolvedTypeKind = cache.computeIfAbsent(qualifiedName) { doResolve(it) }

    private fun doResolve(name: String): ResolvedTypeKind {
        // Primitives
        primitiveMap[name]?.let { return ResolvedTypeKind.Jre(it) }

        // User-defined types (present in the source roots)
        if (name in userTypeNames || userTypeNames.any { it.endsWith(".$name") || it == name }) {
            val fqn = userTypeNames.firstOrNull { it == name || it.endsWith(".$name") } ?: name
            return ResolvedTypeKind.UserClass(fqn)
        }

        // Try solver for JRE/external classpath
        return try {
            val ref = combinedTypeSolver.tryToSolveType(name)
            if (ref.isSolved) ResolvedTypeKind.Jre(name) else ResolvedTypeKind.External
        } catch (_: Exception) {
            ResolvedTypeKind.External
        }
    }

    sealed class ResolvedTypeKind {
        data class UserClass(
            val qualifiedName: String,
        ) : ResolvedTypeKind()

        data class Jre(
            val canonical: String,
        ) : ResolvedTypeKind()

        data object External : ResolvedTypeKind()
    }
}
