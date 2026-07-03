package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.support.DiagnosticCollector
import dev.kuml.uml.UmlClass
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.isAbstract

/**
 * Maps a [KtClass] (not interface, not enum) to a [UmlClass].
 */
internal class KtClassMapper(
    private val propertyMapper: KtPropertyMapper,
    private val functionMapper: KtFunctionMapper,
    private val diagnostics: DiagnosticCollector,
) {
    fun map(
        ktClass: KtClass,
        fqn: String,
        id: String,
    ): UmlClass {
        val name = ktClass.name ?: "_"
        val visibility = KtVisibilityMapper.map(ktClass)
        val isAbstract = ktClass.isAbstract()

        val stereotypes =
            KtDataClassClassifier
                .stereotypesFor(ktClass)
                .filter { it != "abstract" } // isAbstract flag handles that

        // Report nested classes as INFO diagnostics (still emit them as top-level)
        val nested = ktClass.declarations.filterIsInstance<KtClass>()
        for (nestedCls in nested) {
            diagnostics.info(
                "REV-K-020",
                "Nested class '${nestedCls.name}' inside '$name' emitted as top-level classifier.",
                file = ktClass.containingFile.name,
            )
        }

        // Primary constructor val/var params → attributes
        val primaryCtorProps =
            ktClass.primaryConstructor
                ?.valueParameters
                ?.filter { it.hasValOrVar() }
                ?.map { param -> propertyMapper.fromParameter(param, id) }
                ?: emptyList()

        // Body properties → attributes
        val bodyProps =
            ktClass.declarations
                .filterIsInstance<KtProperty>()
                .map { prop -> propertyMapper.map(prop, id) }

        val attributes = (primaryCtorProps + bodyProps).sortedBy { it.name }

        // Operations: primary ctor + secondary ctors + member functions
        val ctorOps =
            buildList {
                ktClass.primaryConstructor?.let { add(functionMapper.fromConstructor(it, id, name, index = 0)) }
                ktClass.secondaryConstructors.forEachIndexed { idx, ctor ->
                    add(functionMapper.fromConstructor(ctor, id, name, index = idx + 1))
                }
            }

        val memberOps =
            ktClass.declarations
                .filterIsInstance<org.jetbrains.kotlin.psi.KtNamedFunction>()
                .map { func -> functionMapper.map(func, id) }

        val operations = (ctorOps + memberOps).sortedBy { it.name }

        return UmlClass(
            id = id,
            name = name,
            visibility = visibility,
            isAbstract = isAbstract,
            attributes = attributes,
            operations = operations,
            stereotypes = stereotypes,
        )
    }
}
