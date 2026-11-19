package dev.kuml.desktop.workspace

import dev.kuml.desktop.AppState
import dev.kuml.workspace.OkfWorkspace
import dev.kuml.workspace.WorkspaceMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

class WorkspaceOpenerTest :
    FunSpec({
        val root: File = Files.createTempDirectory("kuml-opener").toFile()
        afterSpec { root.deleteRecursively() }

        fun ws(mode: WorkspaceMode) = OkfWorkspace(root = root, mode = mode, markerFound = false, documents = emptyList())

        test("trusted workspaces open in their detected mode") {
            val d = WorkspaceOpener.decide(workspace = ws(WorkspaceMode.KNOWLEDGE), trusted = true)
            d.shouldBeInstanceOf<WorkspaceOpener.Decision.Open>().mode shouldBe WorkspaceMode.KNOWLEDGE
        }

        test("an untrusted workspace always needs trust, whatever the mode") {
            WorkspaceMode.entries.forEach { m ->
                WorkspaceOpener.decide(workspace = ws(m), trusted = false).shouldBeInstanceOf<WorkspaceOpener.Decision.NeedsTrust>()
            }
            WorkspaceOpener
                .decide(workspace = ws(WorkspaceMode.UNKNOWN), trusted = false, forcedMode = WorkspaceMode.KNOWLEDGE)
                .shouldBeInstanceOf<WorkspaceOpener.Decision.NeedsTrust>()
                .forcedMode shouldBe WorkspaceMode.KNOWLEDGE
        }

        test("unknown mode needs a mode unless forced") {
            WorkspaceOpener
                .decide(
                    workspace = ws(WorkspaceMode.UNKNOWN),
                    trusted = true,
                ).shouldBeInstanceOf<WorkspaceOpener.Decision.NeedsMode>()
            WorkspaceOpener
                .decide(workspace = ws(WorkspaceMode.UNKNOWN), trusted = true, forcedMode = WorkspaceMode.ENGINEERING)
                .shouldBeInstanceOf<WorkspaceOpener.Decision.Open>()
                .mode shouldBe WorkspaceMode.ENGINEERING
            shouldThrow<IllegalArgumentException> {
                WorkspaceOpener.decide(workspace = ws(WorkspaceMode.KNOWLEDGE), trusted = true, forcedMode = WorkspaceMode.UNKNOWN)
            }
        }

        test("materialize builds the matching OpenWorkspace and rejects UNKNOWN") {
            val files = listOf(File(root, "m.kuml.kts"))
            WorkspaceOpener
                .materialize(workspace = ws(WorkspaceMode.ENGINEERING), mode = WorkspaceMode.ENGINEERING, engineeringFiles = files)
                .shouldBeInstanceOf<OpenWorkspace.Engineering>()
                .scriptFiles shouldBe files
            WorkspaceOpener
                .materialize(
                    workspace = ws(WorkspaceMode.KNOWLEDGE),
                    mode = WorkspaceMode.KNOWLEDGE,
                ).shouldBeInstanceOf<OpenWorkspace.Knowledge>()
            shouldThrow<IllegalArgumentException> {
                WorkspaceOpener.materialize(
                    workspace = ws(WorkspaceMode.UNKNOWN),
                    mode = WorkspaceMode.UNKNOWN,
                )
            }
        }

        test("grantTrustFromUi adds the canonical root once") {
            val state = AppState()
            WorkspaceOpener.grantTrustFromUi(state = state, root = root)
            WorkspaceOpener.grantTrustFromUi(state = state, root = root)
            state.trustedWorkspaces.toList() shouldBe listOf(WorkspaceTrust.canonicalPath(root))
        }

        test("the mcp package never grants trust") {
            val mcpDir: File =
                listOf(
                    File(System.getProperty("user.dir"), "src/jvmMain/kotlin/dev/kuml/desktop/mcp"),
                    File(System.getProperty("user.dir"), "kuml-desktop/src/jvmMain/kotlin/dev/kuml/desktop/mcp"),
                ).firstOrNull { it.isDirectory } ?: error("mcp sources not found")
            val sources = mcpDir.listFiles { f -> f.extension == "kt" }.orEmpty()
            (sources.size > 3) shouldBe true
            val forbidden = listOf("trustedWorkspaces.add", "grantTrustFromUi", "withTrust", "trustedWorkspaces +=")
            val hits = sources.flatMap { f -> forbidden.filter { it in f.readText() }.map { "${f.name} uses $it" } }
            withClue(hits.joinToString("\n")) { hits.shouldBeEmpty() }
        }
    })
