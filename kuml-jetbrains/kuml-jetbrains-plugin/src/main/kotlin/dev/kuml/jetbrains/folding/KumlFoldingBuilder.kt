package dev.kuml.jetbrains.folding

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * Code-folding builder for `.kuml.kts` diagram scripts (V2.0.28c).
 *
 * Registered via `<lang.foldingBuilder language="kotlin" …/>` in `plugin.xml`.
 * Because all `*.kuml.kts` files are parsed by the Kotlin plugin as Kotlin
 * scripts, this builder is invoked for every Kotlin file; it therefore guards
 * the first thing in [buildFoldRegions] to skip non-kUML files cheaply.
 *
 * Extraction is delegated to [KumlFoldingExtractor] — a pure-Kotlin object
 * with no IntelliJ dependencies that is independently unit-tested.
 *
 * ## Folded regions
 *
 * Every DSL lambda block whose function name appears in
 * [KumlFoldingExtractor.DEFAULT_NAMES] becomes a fold region.  The placeholder
 * text shows the call name and the first string argument (if present):
 * - `classOf("User") {…}`
 * - `umlModel {…}`
 *
 * [isCollapsedByDefault] returns `false` so regions start expanded on first
 * open — users can collapse them manually or via the IDE "Collapse All" action.
 *
 * @see KumlFoldingExtractor
 */
class KumlFoldingBuilder :
    FoldingBuilderEx(),
    DumbAware {
    override fun buildFoldRegions(
        root: PsiElement,
        document: Document,
        quick: Boolean,
    ): Array<FoldingDescriptor> {
        // Guard: only process .kuml.kts files — this builder is registered for
        // the "kotlin" language, so it is invoked for every Kotlin file.
        if (root.containingFile?.name?.endsWith(".kuml.kts") != true) return emptyArray()

        val text = root.text ?: return emptyArray()
        val candidates = KumlFoldingExtractor.candidates(text)
        if (candidates.isEmpty()) return emptyArray()

        return candidates
            .mapNotNull { candidate ->
                val range = TextRange(candidate.lambdaStartOffset, candidate.lambdaEndOffset + 1)
                // Validate range fits within the document — malformed scripts could
                // produce out-of-bounds offsets.
                if (range.endOffset > text.length) return@mapNotNull null
                FoldingDescriptor(root.node, range, null, candidate.placeholder)
            }.toTypedArray()
    }

    /**
     * Fallback placeholder — used when the IDE queries the placeholder for an
     * [ASTNode]-keyed descriptor that does not carry its own text (e.g. after
     * PSI invalidation).  In practice [buildFoldRegions] always supplies the
     * placeholder directly via the four-argument [FoldingDescriptor] constructor.
     */
    override fun getPlaceholderText(node: ASTNode): String = "{…}"

    /** Regions start expanded so the script remains fully readable on first open. */
    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}
