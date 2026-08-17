package dev.kuml.jetbrains.markdown

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.Language
import org.intellij.plugins.markdown.injection.CodeFenceLanguageProvider

/**
 * Maps `kuml` code fence info strings to Kotlin syntax highlighting and completion in Markdown documents.
 */
class KumlMarkdownCodeFenceLanguageProvider : CodeFenceLanguageProvider {
    override fun getLanguageByInfoString(infoString: String): Language? {
        val trimmed = infoString.trim()
        if (trimmed.equals("kuml", ignoreCase = true) ||
            trimmed.startsWith("kuml ", ignoreCase = true) ||
            trimmed.startsWith("kuml\t", ignoreCase = true) ||
            trimmed.startsWith("kuml{", ignoreCase = true)
        ) {
            return Language.findLanguageByID("kotlin")
                ?: resolveKotlinLanguageReflective()
        }
        return null
    }

    private fun resolveKotlinLanguageReflective(): Language? =
        try {
            val clazz = Class.forName("org.jetbrains.kotlin.idea.KotlinLanguage")
            clazz.getField("INSTANCE").get(null) as? Language
        } catch (_: Throwable) {
            null
        }

    override fun getCompletionVariantsForInfoString(parameters: CompletionParameters): List<LookupElement> =
        listOf(
            LookupElementBuilder
                .create("kuml")
                .withTypeText("kUML Diagram", true)
                .withPresentableText("kuml"),
        )
}
