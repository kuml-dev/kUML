package dev.kuml.style.worker

/**
 * The kUML DSL's `defaultImports`, copied verbatim from
 * `dev.kuml.core.script.KumlScriptCompilationConfiguration` (`:kuml-core:kuml-core-script`,
 * `KumlScript.kt`). Duplicated rather than shared: this module must never
 * depend on kuml-core-script (see the module-level KDoc in `build.gradle.kts`
 * for why), so these 17 default imports are the one piece of that module's
 * knowledge this worker genuinely needs to resolve unqualified DSL calls
 * (`classOf(...)`, `diagram(...)`, …) the same way a real `*.kuml.kts` script
 * would.
 *
 * If `KumlScript.kt`'s `defaultImports` ever changes, this list must be
 * updated to match — otherwise the style check would either miss owned calls
 * (import list too narrow) or fail to resolve symbols it should see (too
 * wide is harmless; too narrow silently under-reports).
 */
internal val KUML_DEFAULT_IMPORTS: List<String> =
    listOf(
        "dev.kuml.core.model.*",
        "dev.kuml.core.model.DiagramType.*",
        "dev.kuml.core.dsl.*",
        "dev.kuml.uml.dsl.*",
        "dev.kuml.uml.*",
        "dev.kuml.uml.Visibility.*",
        "dev.kuml.uml.AggregationKind.*",
        "dev.kuml.c4.dsl.*",
        "dev.kuml.c4.model.*",
        "dev.kuml.blueprint.dsl.*",
        "dev.kuml.blueprint.model.*",
        "dev.kuml.bpmn.dsl.*",
        "dev.kuml.bpmn.model.*",
        "dev.kuml.sysml2.dsl.*",
        "dev.kuml.sysml2.*",
        "dev.kuml.erm.dsl.*",
        "dev.kuml.erm.model.*",
    )

/**
 * Result of [wrapKumlScript]: the synthetic `.kt` source text handed to the
 * Analysis API, plus [prefixLen] — the exact character count prepended ahead
 * of the original source. Every PSI offset the analyzer sees inside the
 * wrapped body maps back to the original source via a **constant
 * subtraction**: `originalOffset = wrappedOffset - prefixLen`.
 */
internal data class WrappedKumlScript(
    val wrappedText: String,
    val prefixLen: Int,
)

/**
 * Wraps a `*.kuml.kts` source string into a synthetic `.kt` file the plain
 * Kotlin Analysis API (which has no script-definition plumbing wired up here)
 * can build a source module from.
 *
 * `.kuml.kts` scripts are top-level statement sequences (script semantics);
 * `.kt` files require all executable code inside a declaration. So the
 * original body is moved, byte-for-byte, into the body of a synthetic
 * function — and the [KUML_DEFAULT_IMPORTS] the real kUML scripting host
 * would inject automatically are added explicitly as `import` statements
 * ahead of it, so unqualified DSL calls resolve exactly as they would in a
 * real script evaluation.
 *
 * Any `import ...` or `@file:...` line already present in the script body is
 * **not** left in place (both are only legal before the first declaration in
 * a `.kt` file, but the wrapped body sits inside a function) — it is hoisted
 * out to the synthetic prefix and blanked to spaces of the *same length* at
 * its original position. Blanking-in-place (rather than deleting) is what
 * keeps every other line's offset unchanged, so the wrapped-offset →
 * original-offset mapping stays a single constant subtraction instead of a
 * per-line correction table.
 *
 * This is a pure function — no Analysis API, no I/O — so it is directly
 * unit-testable.
 */
internal fun wrapKumlScript(source: String): WrappedKumlScript {
    val lines = source.split("\n").toMutableList()
    val hoistedImports = mutableListOf<String>()
    for (i in lines.indices) {
        val trimmed = lines[i].trim()
        if (trimmed.startsWith("import ")) {
            hoistedImports += trimmed
            lines[i] = " ".repeat(lines[i].length)
        } else if (trimmed.startsWith("@file:")) {
            // @file:Suppress(...) etc. — not valid inside a function body;
            // blank it out. It carries no information the analyzer needs
            // (it never affects which dev.kuml.* symbol a call resolves to).
            lines[i] = " ".repeat(lines[i].length)
        }
    }
    val body = lines.joinToString("\n")
    val prefix =
        buildString {
            KUML_DEFAULT_IMPORTS.forEach { append("import ").append(it).append('\n') }
            hoistedImports.forEach { append(it).append('\n') }
            append("@Suppress(\"UNUSED_EXPRESSION\", \"UNUSED_VARIABLE\")\n")
            append("fun __kumlStyleCheckWrapper__() {\n")
        }
    return WrappedKumlScript(wrappedText = prefix + body + "\n}\n", prefixLen = prefix.length)
}
