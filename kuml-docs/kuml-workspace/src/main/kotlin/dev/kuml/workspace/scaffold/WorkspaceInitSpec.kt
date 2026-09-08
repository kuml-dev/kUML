package dev.kuml.workspace.scaffold

import dev.kuml.workspace.OkfType

/**
 * Resolved specification for a `workspace init` scaffold (V3.6.2, FT-4).
 *
 * All derived fields are computed by [WorkspaceInitSpec.Companion.from].
 *
 * Moved (V3.x, FT-Desktop-New-Workspace) from `dev.kuml.cli.workspace.WorkspaceInitCommand`
 * into this standalone `kuml-workspace` module, so that both the `kuml workspace init` CLI
 * command and kuml-desktop's "New Workspace…" dialog can build the exact same spec without
 * `kuml-desktop` depending on `kuml-cli`. Behavior is unchanged — this is a mechanical
 * package move plus `internal` → `public` visibility (required for cross-module access
 * under `explicitApi()`), and [kumlVersion]'s default now reads from
 * [WorkspaceKumlVersion] instead of `dev.kuml.cli.KumlVersion` (see that object's KDoc).
 */
public data class WorkspaceInitSpec(
    val name: String,
    val slug: String,
    val mode: String,
    val kumlVersion: String = WorkspaceKumlVersion.version,
    val vocabularyVersion: String = OkfType.VOCABULARY_VERSION,
    val okfVersion: String = "0.1",
) {
    public fun toVars(): Map<String, String> =
        mapOf(
            "name" to name,
            "nameKotlinLiteral" to escapeKotlinStringLiteral(name),
            "slug" to slug,
            "mode" to mode,
            "kumlVersion" to kumlVersion,
            "vocabularyVersion" to vocabularyVersion,
            "okfVersion" to okfVersion,
        )

    public companion object {
        private val SLUG_REGEX = Regex("[^a-z0-9]+")

        public fun from(
            name: String,
            mode: String,
        ): WorkspaceInitSpec =
            WorkspaceInitSpec(
                name = name,
                slug = slugify(name),
                mode = mode,
            )

        /**
         * Lowercases [name], replaces any run of non-`[a-z0-9]` characters with a single `-`,
         * trims leading/trailing `-`, and falls back to `"workspace"` if nothing safe remains
         * (e.g. a name of just `"!!!"` or an empty string). Used both as the scaffold's default
         * output directory name and inside generated file content/paths (e.g.
         * `{{slug}}.kuml.kts` in engineering mode) — restricting the result to `[a-z0-9-]`
         * means it can never contain `/`, `\`, or `..` and therefore cannot escape the target
         * directory when substituted into an output path.
         */
        public fun slugify(name: String): String {
            val cleaned =
                name
                    .lowercase()
                    .replace(SLUG_REGEX, "-")
                    .trim('-')
            return cleaned.ifEmpty { "workspace" }
        }

        /**
         * Escapes [value] for safe interpolation inside a Kotlin double-quoted string literal.
         *
         * Templates that embed `{{name}}` inside compiled kUML DSL script blocks (e.g.
         * `classDiagram(name = "{{nameKotlinLiteral}}")` in `main.kuml.kts.tmpl` and the
         * fenced ` ```kuml ` block in `domain-classes.md.tmpl`) must use this escaped variant
         * instead of the raw `{{name}}`. Without it, a workspace name containing a double
         * quote or backslash produces invalid Kotlin source (unbalanced string literal), and a
         * `$` is parsed as the start of a Kotlin string template (`$foo` / `${expr}`) — both
         * break the documented "passes validate with zero findings and renders cleanly"
         * invariant. Newlines/carriage returns/tabs are also escaped since a raw line break
         * inside a single-quoted Kotlin string literal is a compile error.
         *
         * Backslashes are escaped first so the backslashes introduced by the later
         * replacements are not themselves re-escaped.
         */
        public fun escapeKotlinStringLiteral(value: String): String =
            value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
    }
}
