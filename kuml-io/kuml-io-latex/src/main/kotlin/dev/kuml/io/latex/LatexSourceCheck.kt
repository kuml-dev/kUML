package dev.kuml.io.latex

/** Cheap structural checks on generated TikZ source. */
public object LatexSourceCheck {
    private val DEFINITION = Regex("""(?:\\node|node|\\coordinate)\s*(?:\[[^\]]*\]\s*)?\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)""")
    private val REFERENCE = Regex("""\(([A-Za-z_][A-Za-z0-9_]*)(?:\.[a-z ]+)?\)""")

    /**
     * Heuristic: names referenced as `(name)` or `(name.anchor)` that no `\node`, `node` or `\coordinate` in [tex]
     * defines. A figure with such a reference does not compile on its own.
     */
    public fun undefinedNodeReferences(tex: String): Set<String> {
        val code = stripComments(tex)
        val defined = DEFINITION.findAll(code).map { it.groupValues[1] }.toSet()
        val stripped = removeGroups(code)
        return REFERENCE
            .findAll(stripped)
            .map { it.groupValues[1] }
            .filter { it !in defined }
            .toSet()
    }

    private fun stripComments(tex: String): String =
        tex.lineSequence().joinToString("\n") { line ->
            var i = 0
            var cut = line.length
            while (i < line.length) {
                val c = line[i]
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '%') {
                    cut = i
                    break
                }
                i++
            }
            line.substring(0, cut)
        }

    /** Removes balanced `{...}` and `[...]` groups (innermost first); escaped braces are not groups. */
    private fun removeGroups(code: String): String {
        val sb = StringBuilder(code.length)
        var depth = 0
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '\\' && i + 1 < code.length -> {
                    if (depth == 0) sb.append(c).append(code[i + 1])
                    i += 2
                    continue
                }
                c == '{' || c == '[' -> depth++
                (c == '}' || c == ']') && depth > 0 -> depth--
                depth == 0 -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
