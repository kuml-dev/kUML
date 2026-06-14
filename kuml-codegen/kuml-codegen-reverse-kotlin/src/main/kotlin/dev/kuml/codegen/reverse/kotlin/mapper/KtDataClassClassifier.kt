package dev.kuml.codegen.reverse.kotlin.mapper

import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.psiUtil.isAbstract

/** Returns the list of class-level stereotypes for a [KtClass]. */
internal object KtDataClassClassifier {
    fun stereotypesFor(ktClass: KtClass): List<String> =
        buildList {
            if (ktClass.isData()) add("data")
            if (ktClass.isSealed()) add("sealed")
            if (ktClass.isInner()) add("inner")
            if (ktClass.isValue()) add("value")
            if (ktClass.isAbstract()) add("abstract")
        }
}
