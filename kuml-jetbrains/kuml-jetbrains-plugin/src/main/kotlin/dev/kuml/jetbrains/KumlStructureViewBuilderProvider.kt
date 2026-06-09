package dev.kuml.jetbrains

import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import javax.swing.Icon

/**
 * Provides a Structure View for `.kuml.kts` files (V2.0.28b).
 *
 * Registered via the `com.intellij.structureViewBuilderProvider` extension
 * point in `plugin.xml`. The structure view shows a tree:
 *
 * ```
 * filename.kuml.kts
 *  └─ Diagram elements
 *      ├─ Order        (from classOf(name = "Order"))
 *      ├─ Vehicle      (from partDef("Vehicle"))
 *      └─ Payable      (from interfaceOf(name = "Payable"))
 * ```
 *
 * Element extraction uses lightweight regex — no full PSI traversal. This is
 * intentional for the V2.0.28b MVP: the DSL function calls all follow a small
 * set of predictable patterns, and PSI-level analysis would require loading
 * the Kotlin plugin's PSI model for `.kts` files, which adds significant
 * start-up overhead. A proper PSI-based implementation is tracked as V2.0.28d.
 *
 * ## Extracted patterns
 *
 * | DSL call                          | Regex                            |
 * |-----------------------------------|----------------------------------|
 * | `classOf(name = "Order")`         | `classOf\(name = "(\w+)"`        |
 * | `interfaceOf(name = "Payable")`   | `interfaceOf\(name = "(\w+)"`    |
 * | `partDef("Vehicle")`              | `partDef\("(\w+)"`               |
 * | `stateDef("Idle")`                | `stateDef\("(\w+)"`              |
 * | `actionDef("Calibrate")`          | `actionDef\("(\w+)"`             |
 * | `attributeDef("Speed")`           | `attributeDef\("(\w+)"`          |
 * | `portDef("V2XLink")`              | `portDef\("(\w+)"`               |
 * | `connectionDef("PowerLine")`      | `connectionDef\("(\w+)"`         |
 * | `enumDef("Status")`               | `enumDef\("(\w+)"`               |
 *
 * @see KumlElementExtractor for the pure-Kotlin extraction logic used by tests.
 */
class KumlStructureViewBuilderProvider : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): com.intellij.ide.structureView.StructureViewBuilder? {
        if (!psiFile.name.endsWith(".kuml.kts")) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = KumlStructureViewModel(psiFile)
        }
    }
}

// ── Structure ViewModel ───────────────────────────────────────────────────────

private class KumlStructureViewModel(
    private val psiFile: PsiFile,
) : StructureViewModelBase(psiFile, KumlFileTreeElement(psiFile)),
    StructureViewModel.ElementInfoProvider {
    override fun isAlwaysShowsPlus(element: StructureViewTreeElement) = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement) = element is KumlDslElementTreeElement
}

// ── Root tree element — the file itself ──────────────────────────────────────

private class KumlFileTreeElement(
    private val psiFile: PsiFile,
) : StructureViewTreeElement {
    override fun getValue() = psiFile

    override fun getPresentation() =
        object : ItemPresentation {
            override fun getPresentableText() = psiFile.name

            override fun getLocationString(): String? = null

            override fun getIcon(unused: Boolean): Icon? = null
        }

    override fun getChildren(): Array<TreeElement> {
        val text = psiFile.text ?: return emptyArray()
        val names = KumlElementExtractor.extractAll(text)
        return names.map { KumlDslElementTreeElement(it) }.toTypedArray()
    }

    override fun navigate(requestFocus: Boolean) = Unit

    override fun canNavigate() = false

    override fun canNavigateToSource() = false
}

// ── Leaf tree element — a single extracted DSL name ──────────────────────────

private class KumlDslElementTreeElement(
    private val name: String,
) : StructureViewTreeElement {
    override fun getValue() = name

    override fun getPresentation() =
        object : ItemPresentation {
            override fun getPresentableText() = name

            override fun getLocationString(): String? = null

            override fun getIcon(unused: Boolean): Icon? = null
        }

    override fun getChildren(): Array<TreeElement> = emptyArray()

    override fun navigate(requestFocus: Boolean) = Unit

    override fun canNavigate() = false

    override fun canNavigateToSource() = false
}

// ── Pure extraction logic — testable without IntelliJ ─────────────────────────

/**
 * Extracts element names from `.kuml.kts` script text using regex patterns.
 *
 * This object is intentionally free of IntelliJ platform dependencies so it
 * can be tested in unit tests without a running IDE. [KumlStructureViewBuilderProvider]
 * delegates to this object for the actual extraction work.
 */
object KumlElementExtractor {
    private val PATTERNS =
        listOf(
            // SysML 2 / kUML DSL patterns — positional first arg (string literal)
            Regex("""partDef\s*\(\s*"(\w[\w\s]*)""""),
            Regex("""stateDef\s*\(\s*"(\w[\w\s]*)""""),
            Regex("""actionDef\s*\(\s*"(\w[\w\s]*)""""),
            Regex("""attributeDef\s*\(\s*"(\w[\w\s]*)""""),
            Regex("""portDef\s*\(\s*"(\w[\w\s]*)""""),
            Regex("""connectionDef\s*\(\s*"(\w[\w\s]*)""""),
            Regex("""enumDef\s*\(\s*"(\w[\w\s]*)""""),
            Regex("""requirementDef\s*\(\s*"(\w[\w\s]*)""""),
            // UML DSL patterns — named `name` parameter
            Regex("""classOf\s*\(\s*name\s*=\s*"(\w[\w\s]*)""""),
            Regex("""interfaceOf\s*\(\s*name\s*=\s*"(\w[\w\s]*)""""),
            Regex("""enumOf\s*\(\s*name\s*=\s*"(\w[\w\s]*)""""),
            Regex("""componentOf\s*\(\s*name\s*=\s*"(\w[\w\s]*)""""),
            // Diagram declarations
            Regex("""stmDiagram\s*\(\s*"([^"]+)""""),
            Regex("""bdd\s*\(\s*"([^"]+)""""),
            Regex("""actDiagram\s*\(\s*"([^"]+)""""),
        )

    /**
     * Extract all recognisable element names from [scriptText].
     *
     * Deduplicates names while preserving first-occurrence order.
     * Returns an empty list for empty or unrecognised scripts.
     *
     * @param scriptText Full text of a `.kuml.kts` file.
     * @return Ordered, deduplicated list of element names.
     */
    fun extractAll(scriptText: String): List<String> {
        if (scriptText.isBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (pattern in PATTERNS) {
            for (match in pattern.findAll(scriptText)) {
                val name = match.groupValues[1].trim()
                if (name.isNotEmpty()) seen.add(name)
            }
        }
        return seen.toList()
    }
}
