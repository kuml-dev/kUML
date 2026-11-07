package dev.kuml.workspace.latex

import dev.kuml.io.latex.LatexFamily
import dev.kuml.workspace.OkfWorkspace
import dev.kuml.workspace.WorkspaceScanner
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.imageio.ImageIO

/** Throw-away workspaces and a fake diagram source: these tests never run kUML scripts. */
object LatexTestSupport {
    const val GOOD_TEX =
        "\\begin{tikzpicture}\n  \\node[draw] (n_a) at (0pt, 0pt) {A};\n  \\node[draw] (n_b) at (80pt, 0pt) {B};\n" +
            "  \\draw (n_a) -- (n_b);\n\\end{tikzpicture}\n"
    const val DANGLING_TEX = "\\begin{tikzpicture}\n  \\node (n_a) at (0pt, 0pt) {A};\n  \\draw (n_a) -- (n_missing);\n\\end{tikzpicture}\n"

    val CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC)

    fun tempDir(): File = Files.createTempDirectory("kuml-latex-test").toFile()

    fun workspace(
        files: Map<String, String>,
        dir: File = tempDir(),
    ): OkfWorkspace {
        files.forEach { (rel, text) ->
            val f = File(dir, rel)
            f.parentFile.mkdirs()
            f.writeText(text, Charsets.UTF_8)
        }
        return WorkspaceScanner.scan(root = dir)
    }

    fun png(
        file: File,
        w: Int = 16,
        h: Int = 16,
    ) {
        file.parentFile.mkdirs()
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", file)
    }

    fun index(
        links: List<String>,
        extraFrontmatter: String = "",
    ): String =
        "---\ntype: KumlWorkspace\ntitle: Handbuch\nlang: de\n$extraFrontmatter---\n# Handbuch\n\n" +
            links.joinToString("\n") { "- [$it]($it)" } +
            "\n"

    fun doc(
        title: String,
        body: String,
    ): String = "---\ntype: Article\ntitle: $title\n---\n# $title\n\n$body\n"

    fun options(configure: LatexKnowledgeOptions.() -> LatexKnowledgeOptions = { this }) =
        LatexKnowledgeOptions(generator = "kUML test").configure()

    fun source(tex: String = GOOD_TEX): LatexDiagramSource =
        LatexDiagramSource { _, _, _ ->
            LatexDiagramOutcome.Rendered(tex = tex, family = LatexFamily.UML, diagramCount = 1, diagramName = null)
        }

    fun prepare(
        ws: OkfWorkspace,
        options: LatexKnowledgeOptions = options(),
        diagrams: LatexDiagramSource = source(),
    ): PreparedLatexKnowledge = LatexKnowledgeExporter.prepare(workspace = ws, options = options, diagrams = diagrams, clock = CLOCK)

    fun PreparedLatexKnowledge.text(path: String): String = (files.first { it.path == path } as LatexOutputFile.Text).content

    fun PreparedLatexKnowledge.main(): String = text("main.tex")

    fun PreparedLatexKnowledge.write(dir: File) {
        for (f in files) {
            val target = File(dir, f.path)
            target.parentFile.mkdirs()
            when (f) {
                is LatexOutputFile.Text -> target.writeText(f.content, Charsets.UTF_8)
                is LatexOutputFile.Binary -> target.writeBytes(f.bytes)
            }
        }
    }

    fun issues(
        p: PreparedLatexKnowledge,
        level: LatexIssueLevel,
    ) = p.issues.filter { it.level == level }
}
