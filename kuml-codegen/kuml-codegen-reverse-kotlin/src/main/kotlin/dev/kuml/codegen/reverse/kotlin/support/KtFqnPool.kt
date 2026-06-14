package dev.kuml.codegen.reverse.kotlin.support

import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

/**
 * Pass-1 pool: traverses all [KtFile]s and builds a map from FQN to [KtClassOrObject].
 *
 * FQNs for nested classes use dot notation: `OuterClass.InnerClass`.
 * IDs are formatted as `"kt:<fqn>"` for stable cross-file references.
 */
internal class KtFqnPool private constructor(
    private val fqnToDecl: Map<String, KtClassOrObject>,
    private val fqnToId: Map<String, String>,
) {
    fun resolve(fqn: String): KtClassOrObject? = fqnToDecl[fqn]

    fun idOf(fqn: String): String? = fqnToId[fqn]

    fun allFqns(): Set<String> = fqnToDecl.keys

    companion object {
        fun build(
            files: List<KtFile>,
            @Suppress("UNUSED_PARAMETER") diagnostics: DiagnosticCollector,
        ): KtFqnPool {
            val fqnToDecl = mutableMapOf<String, KtClassOrObject>()
            val fqnToId = mutableMapOf<String, String>()

            for (file in files) {
                val pkg = file.packageFqName.asString()
                collectDeclarations(file.declarations.filterIsInstance<KtClassOrObject>(), pkg, null, fqnToDecl, fqnToId)
            }

            return KtFqnPool(fqnToDecl, fqnToId)
        }

        private fun collectDeclarations(
            declarations: List<KtClassOrObject>,
            pkg: String,
            enclosingFqn: String?,
            fqnToDecl: MutableMap<String, KtClassOrObject>,
            fqnToId: MutableMap<String, String>,
        ) {
            for (decl in declarations) {
                val simpleName = decl.name ?: continue
                val fqn =
                    when {
                        enclosingFqn != null -> "$enclosingFqn.$simpleName"
                        pkg.isNotBlank() -> "$pkg.$simpleName"
                        else -> simpleName
                    }
                fqnToDecl[fqn] = decl
                fqnToId[fqn] = "kt:$fqn"

                // Recurse into nested declarations
                val nested = decl.declarations.filterIsInstance<KtClassOrObject>()
                if (nested.isNotEmpty()) {
                    collectDeclarations(nested, pkg, fqn, fqnToDecl, fqnToId)
                }
            }
        }
    }
}
