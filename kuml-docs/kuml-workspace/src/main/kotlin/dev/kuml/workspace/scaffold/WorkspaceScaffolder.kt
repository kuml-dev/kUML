package dev.kuml.workspace.scaffold

import dev.kuml.scaffold.Scaffolder
import dev.kuml.scaffold.TemplateFile
import java.io.File

/**
 * Moved (V3.x, FT-Desktop-New-Workspace) from `dev.kuml.cli.workspace.WorkspaceInitCommand`
 * into this standalone `kuml-workspace` module — see [WorkspaceInitSpec]'s KDoc for the
 * motivation. Behavior is unchanged — mechanical package move plus `internal` → `public`
 * visibility.
 */
public object WorkspaceScaffolder {
    private const val RESOURCE_BASE = "workspace-templates"

    /** Ordered list of template files for each mode. GraalVM-safe — no classpath directory scan. */
    public fun templateFiles(mode: String): List<TemplateFile> {
        val base = "$RESOURCE_BASE/$mode"
        return when (mode) {
            "knowledge" ->
                listOf(
                    TemplateFile(resourcePath = "$base/kuml-workspace.toml.tmpl", outputPath = ".kuml-workspace.toml"),
                    TemplateFile(resourcePath = "$base/index.md.tmpl", outputPath = "index.md"),
                    TemplateFile(resourcePath = "$base/introduction.md.tmpl", outputPath = "articles/01-introduction.md"),
                    TemplateFile(resourcePath = "$base/domain-classes.md.tmpl", outputPath = "models/domain-classes.md"),
                    TemplateFile(resourcePath = "$base/glossary.md.tmpl", outputPath = "glossary/index.md"),
                )
            "engineering" ->
                listOf(
                    TemplateFile(resourcePath = "$base/kuml-workspace.toml.tmpl", outputPath = ".kuml-workspace.toml"),
                    TemplateFile(resourcePath = "$base/main.kuml.kts.tmpl", outputPath = "{{slug}}.kuml.kts"),
                    TemplateFile(resourcePath = "$base/gitignore.tmpl", outputPath = ".gitignore"),
                )
            else -> error("Unknown workspace mode '$mode' — this is a bug in kuml-workspace")
        }
    }

    /**
     * Renders and writes all template files for [spec]'s mode into [targetDir], via the
     * shared [Scaffolder] engine (same mechanics as `kuml plugin init`).
     *
     * @param force When `false`, aborts if [targetDir] already exists and is non-empty.
     */
    public fun scaffold(
        spec: WorkspaceInitSpec,
        targetDir: File,
        force: Boolean,
        echo: (String) -> Unit,
    ) {
        Scaffolder.scaffold(templates = templateFiles(spec.mode), vars = spec.toVars(), targetDir = targetDir, force = force, echo = echo)
    }
}
