package dev.kuml.desktop.mcp

import dev.kuml.desktop.AppState
import dev.kuml.desktop.render.DesktopEngineInit
import dev.kuml.desktop.workspace.DocumentRevision
import dev.kuml.desktop.workspace.OpenWorkspace
import dev.kuml.desktop.workspace.WorkspaceState
import dev.kuml.desktop.workspace.WorkspaceTrust
import dev.kuml.mcp.tools.McpToolException
import dev.kuml.workspace.WorkspaceScanner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files

/**
 * PAL wave F: revision-checked writing of articles/index/glossary (point 20), real-port `render_all` note warnings
 * (21b) and the declared-note statistics (21c). Uses the REAL default render/evaluate ports (script host), not mocks.
 */
class DesktopMcpWorkspaceWelleFTest :
    FunSpec({
        System.setProperty("java.awt.headless", "true")
        DesktopEngineInit.ensure()

        val index = "---\ntype: KumlWorkspace\ntitle: PAL\n---\n# Index\n\n- [Einleitung](articles/01-introduction.md)\n"
        val article = "---\ntype: Article\ntitle: Einleitung\n---\n# Einleitung\n\nText.\n"
        val glossary = "---\ntype: Glossary\ntitle: Glossar\n---\n# Glossar\n\n- Begriff: Erklaerung\n"
        val noted =
            "---\ntype: Concept\ntitle: Notes\n---\n# Notes\n\n```kuml\n" +
                "import dev.kuml.core.model.note.NoteKind\n" +
                "sysml2Model(name = \"NoteDemo\") {\n" +
                "    val vehicle = partDef(name = \"Vehicle\")\n" +
                "    bdd(name = \"Overview\") {\n" +
                "        include(definition = vehicle)\n" +
                "        comment(text = \"a1\", \"Vehicle\", id = \"a1\", kind = NoteKind.ASSUMPTION)\n" +
                "        comment(text = \"a2\", \"Vehicle\", id = \"a2\", kind = NoteKind.ASSUMPTION)\n" +
                "        comment(text = \"r1\", \"Gibtsnicht\", id = \"r1\", kind = NoteKind.RISK)\n" +
                "    }\n" +
                "}\n```\n"

        class Fix {
            val root: File = Files.createTempDirectory("kuml-mcp-wf").toFile()

            init {
                File(root, "index.md").writeText(index)
                File(root, "articles").mkdirs()
                File(root, "articles/01-introduction.md").writeText(article)
                File(root, "glossary").mkdirs()
                File(root, "glossary/glossar.md").writeText(glossary)
                File(root, "models").mkdirs()
                File(root, "models/noted.md").writeText(noted)
            }

            val state = AppState()
            val ws = WorkspaceState(initialWorkspace = WorkspaceScanner.scan(root = root), ioDispatcher = Dispatchers.Unconfined)

            init {
                state.openWorkspace = OpenWorkspace.Knowledge(ws)
                state.trustedWorkspaces.add(WorkspaceTrust.canonicalPath(root))
            }

            // Real default ports (script host), generous timeouts.
            val tools =
                DesktopMcpTools(
                    state = state,
                    render = McpRenderPort { throw IllegalStateException("script render must not run") },
                    validate = McpValidatePort { throw IllegalStateException("script validate must not run") },
                    editorActions = { null },
                    onNotice = {},
                    uiDispatcher = Dispatchers.Unconfined,
                    evalDispatcher = Dispatchers.IO,
                    renderTimeoutMs = 120_000,
                    gateWaitMs = 120_000,
                )

            fun call(
                name: String,
                args: JsonObject = JsonObject(emptyMap()),
            ): JsonObject =
                Json
                    .parseToJsonElement(
                        tools.tools
                            .first { it.descriptor.name == name }
                            .call(args)[0]
                            .text!!,
                    ).jsonObject

            fun error(
                name: String,
                args: JsonObject,
            ): String {
                val e = shouldThrow<McpToolException> { tools.tools.first { it.descriptor.name == name }.call(args) }
                return Json
                    .parseToJsonElement(e.message!!)
                    .jsonObject["error"]!!
                    .jsonPrimitive.content
            }
        }

        fun args(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

        for ((path, original) in listOf(
            "index.md" to index,
            "articles/01-introduction.md" to article,
            "glossary/glossar.md" to glossary,
        )) {
            test("point 20: set_document writes $path with the current revision and conflicts with a stale one") {
                val f = Fix()
                try {
                    val got = f.call("kuml_desktop_workspace_get_document", args("path" to path))
                    val rev = got["revision"]!!.jsonPrimitive.content
                    rev shouldBe DocumentRevision.of(original)
                    val updated = original + "\nZusatz.\n"
                    val stale =
                        f.error(
                            "kuml_desktop_workspace_set_document",
                            args("path" to path, "content" to updated, "baseRevision" to "0000000000000000"),
                        )
                    stale shouldBe "REVISION_CONFLICT"
                    File(f.root, path).readText() shouldBe original
                    val ok =
                        f.call(
                            "kuml_desktop_workspace_set_document",
                            args("path" to path, "content" to updated, "baseRevision" to rev),
                        )
                    ok["revision"]!!.jsonPrimitive.content shouldBe DocumentRevision.of(updated)
                    File(f.root, path).readText() shouldBe updated
                } finally {
                    f.root.deleteRecursively()
                }
            }
        }

        test("21b + 21c: render_all with the real port reports note warnings and declared-note statistics") {
            val f = Fix()
            try {
                val r = f.call("kuml_desktop_workspace_render_all")
                val codes = r["warnings"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }
                withClue(r.toString()) { codes shouldContain "KUML-NOTE-W001" }
                val stats = r["noteStats"]!!.jsonObject
                stats["declared"]!!.jsonPrimitive.int shouldBe 3
                stats["blocks"]!!.jsonPrimitive.int shouldBe 1
                stats["byKind"]!!.jsonObject["ASSUMPTION"]!!.jsonPrimitive.int shouldBe 2
                stats["byKind"]!!.jsonObject["RISK"]!!.jsonPrimitive.int shouldBe 1
            } finally {
                f.root.deleteRecursively()
            }
        }

        test("21a + 21c: validate evaluate=true reports the same statistics; without evaluate there is none but a note") {
            val f = Fix()
            try {
                val plain = f.call("kuml_desktop_workspace_validate")
                (plain["noteStats"] == null) shouldBe true
                (plain["note"] != null) shouldBe true
                val deep = f.call("kuml_desktop_workspace_validate", buildJsonObject { put("evaluate", true) })
                val codes = deep["findings"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }
                withClue(deep.toString()) { codes shouldContain "KUML-NOTE-W001" }
                val stats = deep["noteStats"]!!.jsonObject
                stats["declared"]!!.jsonPrimitive.int shouldBe 3
                stats["byKind"]!!.jsonObject["ASSUMPTION"]!!.jsonPrimitive.int shouldBe 2
            } finally {
                f.root.deleteRecursively()
            }
        }

        test("point 22: [note-prefixes] marker yields KUML-NOTE-W006 in validate evaluate=true and render_all") {
            val f = Fix()
            try {
                val marker = "[workspace]\nmode = \"knowledge\"\n\n[note-prefixes]\nr1 = \"ASSUMPTION\"\n"
                File(f.root, ".kuml-workspace.toml").writeText(marker)
                // r1 text is "r1" and the note has kind RISK, the table expects ASSUMPTION
                val deep = f.call("kuml_desktop_workspace_validate", buildJsonObject { put("evaluate", true) })
                val codes = deep["findings"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }
                withClue(deep.toString()) { codes shouldContain "KUML-NOTE-W006" }
                val all = f.call("kuml_desktop_workspace_render_all")
                val wcodes = all["warnings"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }
                withClue(all.toString()) { wcodes shouldContain "KUML-NOTE-W006" }
                all["noteStats"]!!.jsonObject["declared"]!!.jsonPrimitive.int shouldBe 3
            } finally {
                f.root.deleteRecursively()
            }
        }
    })
