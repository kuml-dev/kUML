package dev.kuml.desktop.workspace

import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.render.DesktopRenderPipeline
import dev.kuml.desktop.render.DesktopRenderResult
import dev.kuml.markdown.CodeBlockExtractor
import dev.kuml.workspace.OkfType
import dev.kuml.workspace.WorkspaceScanner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

/**
 * Proof that every diagram type (and alias) the knowledge workspace advertises really renders in the
 * desktop preview: each fixture under `workspace-fixtures/types/` is scanned, typed and rendered.
 * Structural assertions only (fonts differ between machines).
 */
class WorkspaceTypeFixturesRenderTest :
    FunSpec({

        val fixtureNames =
            listOf(
                "sysml2-bdd",
                "sysml2-ibd",
                "sysml2-internal-block-diagram",
                "sysml2-act",
                "sysml2-seq",
                "sysml2-stm",
                "sysml2-uc",
                "sysml2-req",
                "sysml2-par",
                "uml-interaction-overview",
                "user-journey",
                "c4-deployment",
                "c4-dynamic",
                "c4-landscape",
                "erm-martin",
                "erm-chen",
                "erm-idef1x",
            )

        fun fixtureText(name: String): String =
            WorkspaceTypeFixturesRenderTest::class.java
                .getResourceAsStream("/workspace-fixtures/types/$name.md")!!
                .readBytes()
                .toString(Charsets.UTF_8)

        /** A throw-away workspace containing all fixtures (plus the root marker the scanner expects). */
        fun createWorkspace(): File {
            val root = Files.createTempDirectory("kuml-type-fixtures").toFile()
            File(root, "index.md").writeText("---\ntype: KumlWorkspace\n---\n# Index\n")
            fixtureNames.forEach { File(root, "$it.md").writeText(fixtureText(it)) }
            return root
        }

        test("the fixture set covers every alias of the vocabulary and the new C4 types") {
            val used =
                fixtureNames.map { name ->
                    fixtureText(name)
                        .lineSequence()
                        .first { it.startsWith("type:") }
                        .removePrefix("type:")
                        .trim()
                }
            val needed = OkfType.entries.flatMap { it.aliases } + listOf("C4DeploymentDiagram", "C4DynamicDiagram", "C4LandscapeDiagram")
            needed.filter { it !in used } shouldBe emptyList()
        }

        test("every fixture resolves to a diagram type that expects a kuml block") {
            val root = createWorkspace()
            try {
                val docs = WorkspaceScanner.scan(root = root).documents.filter { it.relativePath != "index.md" }
                docs.size shouldBe fixtureNames.size
                docs.forEach { doc ->
                    val type = doc.type.shouldNotBeNull()
                    type.requiresKumlBlock shouldBe true
                    doc.kumlBlocks.size shouldBe 1
                }
            } finally {
                root.deleteRecursively()
            }
        }

        for (name in fixtureNames) {
            test("$name renders to an SVG through the desktop pipeline") {
                val block = CodeBlockExtractor.extract(fixtureText(name)).first()
                val result = DesktopRenderPipeline.render(script = block.source, themeName = "kuml")
                val svg = result.shouldBeInstanceOf<DesktopRenderResult.Svg>()
                svg.svg shouldContain "<svg"
            }
        }

        test("selecting each fixture in the workspace state gives an SVG and no error") {
            val root = createWorkspace()
            try {
                val state = WorkspaceState(initialWorkspace = WorkspaceScanner.scan(root = root))
                for (name in fixtureNames) {
                    val doc = state.documents.first { it.relativePath == "$name.md" }
                    state.select(doc = doc, themeName = "kuml", strings = Strings.EN)
                    state.docError shouldBe null
                    state.docSvg.shouldNotBeNull() shouldContain "<svg"
                }
            } finally {
                root.deleteRecursively()
            }
        }

        test("an ERM note with a placement hint is drawn and does not trigger an undrawn or ignored-hint warning") {
            val block = CodeBlockExtractor.extract(fixtureText("erm-idef1x")).first()
            val result =
                DesktopRenderPipeline
                    .render(script = block.source, themeName = "kuml")
                    .shouldBeInstanceOf<DesktopRenderResult.Svg>()
            result.svg shouldContain "Fixture note"
            result.warnings.joinToString("\n") shouldNotContain "KUML-NOTE-W002"
            result.warnings.joinToString("\n") shouldNotContain "KUML-NOTE-W005"
        }
    })
