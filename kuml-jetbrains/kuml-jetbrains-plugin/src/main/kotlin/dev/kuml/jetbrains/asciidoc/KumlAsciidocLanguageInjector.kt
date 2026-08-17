package dev.kuml.jetbrains.asciidoc

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import org.asciidoc.intellij.psi.AsciiDocListing

/**
 * Injects the Kotlin language into AsciiDoc `[source,kuml]` listing bodies so
 * editors get Kotlin highlighting / completion inside diagram fences.
 *
 * Mirrors [dev.kuml.jetbrains.markdown.KumlMarkdownCodeFenceLanguageProvider]'s
 * reflective Kotlin-language lookup.
 */
class KumlAsciidocLanguageInjector : MultiHostInjector {
    override fun getLanguagesToInject(
        registrar: MultiHostRegistrar,
        context: PsiElement,
    ) {
        if (context !is AsciiDocListing) return
        // AsciiDocListing implements PsiLanguageInjectionHost via AbstractAsciiDocCodeBlock.
        if (!context.isValidHost) return

        val fenceLanguage = context.fenceLanguage ?: return
        if (!KumlAsciidocLineMarkerProvider.isKumlFenceLanguage(fenceLanguage)) return

        val kotlin = resolveKotlinLanguage() ?: return
        val contentRange =
            try {
                context.contentTextRange
            } catch (_: Throwable) {
                null
            } ?: return
        if (contentRange.isEmpty) return

        // contentTextRange is absolute (document offsets); injection hosts need host-relative ranges.
        val hostRange = context.textRange
        val relativeStart = (contentRange.startOffset - hostRange.startOffset).coerceAtLeast(0)
        val relativeEnd = (contentRange.endOffset - hostRange.startOffset).coerceAtMost(context.textLength)
        if (relativeStart >= relativeEnd) return
        val relative = TextRange(relativeStart, relativeEnd)

        registrar
            .startInjecting(kotlin)
            .addPlace(null, null, context as PsiLanguageInjectionHost, relative)
            .doneInjecting()
    }

    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(AsciiDocListing::class.java)

    private fun resolveKotlinLanguage(): Language? =
        Language.findLanguageByID("kotlin")
            ?: resolveKotlinLanguageReflective()

    private fun resolveKotlinLanguageReflective(): Language? =
        try {
            val clazz = Class.forName("org.jetbrains.kotlin.idea.KotlinLanguage")
            clazz.getField("INSTANCE").get(null) as? Language
        } catch (_: Throwable) {
            null
        }
}
