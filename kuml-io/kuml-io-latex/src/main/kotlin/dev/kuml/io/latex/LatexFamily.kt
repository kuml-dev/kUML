package dev.kuml.io.latex

/** Diagram families with LaTeX output and the TikZ libraries each needs on top of the base set. */
public enum class LatexFamily(
    internal val extraTikzLibraries: List<String>,
) {
    UML(emptyList()),
    SYSML2(emptyList()),
    C4(emptyList()),
    BPMN(listOf("shapes.geometric")),
    BLUEPRINT(listOf("shapes.geometric", "plotmarks")),

    /** ERM (Martin notation): crow's-foot glyphs are plain lines and circles, no library beyond the base set. */
    ERM(emptyList()),
}
