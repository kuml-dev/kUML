package dev.kuml.desktop.mcp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kuml.cli.workspace.WorkspaceRenderer
import dev.kuml.desktop.AppState
import dev.kuml.desktop.render.DesktopEngineInit
import dev.kuml.desktop.workspace.DocumentRevision
import dev.kuml.desktop.workspace.OpenWorkspace
import dev.kuml.desktop.workspace.WorkspaceState
import dev.kuml.desktop.workspace.WorkspaceTrust
import dev.kuml.mcp.McpContent
import dev.kuml.mcp.tools.McpToolException
import dev.kuml.workspace.OkfFinding
import dev.kuml.workspace.OkfSeverity
import dev.kuml.workspace.WorkspaceScanner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DesktopMcpWorkspaceToolsTest :
    FunSpec({
        System.setProperty("java.awt.headless", "true")
        DesktopEngineInit.ensure()

        val diagramDoc =
            "---\ntype: UmlClassDiagram\ntitle: A\n---\n# A\n\n```kuml\nclassDiagram(name = \"Test\") {\n" +
                "    classOf(name = \"Fahrzeug\") { }\n}\n```\n"
        val proseDoc = "---\ntype: Concept\ntitle: P\n---\n# Prose\n"
        val newDoc = "---\ntype: Concept\ntitle: N\n---\n# New\n"
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

        class Fix(
            trusted: Boolean = true,
            uiDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
            uiTimeoutMs: Long = 5_000,
            val renderTimeoutMs: Long = 5_000,
            gateWaitMs: Long = 5_000,
            val blockRender: CountDownLatch? = null,
        ) {
            val root: File = Files.createTempDirectory("kuml-mcp-ws").toFile()
            val notices = mutableListOf<McpNotice>()
            val renderTmpDirs = mutableListOf<File>()
            var evaluateCalls = 0
            var renderCalls = 0

            init {
                File(root, "index.md").writeText("---\ntype: KumlWorkspace\n---\n# Index\n")
                File(root, "models").mkdirs()
                File(root, "models/a.md").writeText(diagramDoc)
                File(root, "notes").mkdirs()
                File(root, "notes/p.md").writeText(proseDoc)
            }

            val state = AppState()
            val ws = WorkspaceState(initialWorkspace = WorkspaceScanner.scan(root = root), ioDispatcher = Dispatchers.Unconfined)

            init {
                state.openWorkspace = OpenWorkspace.Knowledge(ws)
                if (trusted) state.trustedWorkspaces.add(WorkspaceTrust.canonicalPath(root))
            }

            val tools =
                DesktopMcpTools(
                    state = state,
                    render = McpRenderPort { throw IllegalStateException("script render must not run") },
                    validate = McpValidatePort { throw IllegalStateException("script validate must not run") },
                    editorActions = { null },
                    onNotice = { notices += it },
                    uiDispatcher = uiDispatcher,
                    evalDispatcher = Dispatchers.IO,
                    uiTimeoutMs = uiTimeoutMs,
                    renderTimeoutMs = renderTimeoutMs,
                    gateWaitMs = gateWaitMs,
                    workspaceRender =
                        McpWorkspaceRenderPort { docs, out, format, _, _, _ ->
                            renderCalls++
                            renderTmpDirs += out
                            blockRender?.await(10, TimeUnit.SECONDS)
                            val rendered = mutableListOf<File>()
                            val skipped = mutableListOf<String>()
                            docs.forEach { d ->
                                if (d.kumlBlocks.isEmpty()) {
                                    skipped += d.relativePath
                                } else {
                                    d.kumlBlocks.forEachIndexed { i, _ ->
                                        val f = File(out, "${d.file.nameWithoutExtension}-$i.$format")
                                        if (format == "png") f.writeBytes(png) else f.writeText("<svg>fake ${d.relativePath}</svg>")
                                        rendered += f
                                    }
                                }
                            }
                            WorkspaceRenderer.RenderReport(rendered = rendered, skipped = skipped, failures = emptyList())
                        },
                    workspaceEvaluate =
                        McpWorkspaceEvaluatePort { _, _ ->
                            evaluateCalls++
                            listOf(
                                OkfFinding(
                                    code = "OKF-E-007",
                                    severity = OkfSeverity.ERROR,
                                    file = "models/a.md",
                                    line = 8,
                                    message = "boom",
                                ),
                            )
                        },
                )

            fun contents(
                name: String,
                args: JsonObject = JsonObject(emptyMap()),
            ): List<McpContent> = tools.tools.first { it.descriptor.name == name }.call(args)

            fun call(
                name: String,
                args: JsonObject = JsonObject(emptyMap()),
            ): JsonObject = Json.parseToJsonElement(contents(name, args)[0].text!!).jsonObject

            fun error(
                name: String,
                args: JsonObject = JsonObject(emptyMap()),
            ): JsonObject {
                val e = shouldThrow<McpToolException> { contents(name, args) }
                val msg = e.message!!
                msg shouldNotContain root.path
                msg shouldNotContain root.canonicalPath
                return Json.parseToJsonElement(msg).jsonObject
            }

            fun code(
                name: String,
                args: JsonObject = JsonObject(emptyMap()),
            ): String = error(name, args)["error"]!!.jsonPrimitive.content

            fun disk(rel: String) = File(root, rel).readText()

            fun rev(rel: String) = DocumentRevision.of(disk(rel))

            fun cleanup() = root.deleteRecursively()
        }

        fun args(vararg pairs: Pair<String, Any?>) =
            buildJsonObject {
                pairs.forEach { (k, v) ->
                    when (v) {
                        null -> {}
                        is Boolean -> put(k, v)
                        is Int -> put(k, v)
                        else -> put(k, v.toString())
                    }
                }
            }

        suspend fun withFix(
            fix: Fix = Fix(),
            body: suspend (Fix) -> Unit,
        ) {
            try {
                body(fix)
            } finally {
                fix.cleanup()
            }
        }

        test("no workspace open: every workspace tool answers NO_WORKSPACE_OPEN") {
            val f = Fix()
            try {
                f.state.openWorkspace = null
                val argsFor = args("path" to "a.md", "content" to "x", "confirm" to true)
                DesktopMcpWorkspaceTools.TOOL_NAMES.forEach { name -> f.code(name, argsFor) shouldBe "NO_WORKSPACE_OPEN" }
            } finally {
                f.cleanup()
            }
        }

        test("engineering workspace: info and list work, the rest is unsupported") {
            val f = Fix()
            try {
                f.state.openWorkspace = OpenWorkspace.Engineering(root = f.root, scriptFiles = listOf(File(f.root, "m.kuml.kts")))
                File(f.root, "m.kuml.kts").writeText("// x")
                f.call("kuml_desktop_workspace_info")["mode"]!!.jsonPrimitive.content shouldBe "engineering"
                f.call("kuml_desktop_workspace_list_documents")["total"]!!.jsonPrimitive.int shouldBe 1
                val rest = DesktopMcpWorkspaceTools.TOOL_NAMES.drop(2)
                rest.forEach { name ->
                    val e = f.error(name, args("path" to "a.md", "content" to "x", "confirm" to true))
                    e["error"]!!.jsonPrimitive.content shouldBe "WORKSPACE_MODE_UNSUPPORTED"
                    e["mode"]!!.jsonPrimitive.content shouldBe "engineering"
                }
            } finally {
                f.cleanup()
            }
        }

        test("get_last_error reports the workspace document error") {
            withFix { f ->
                f.ws.select(
                    doc = f.ws.documents.first { it.relativePath == "notes/p.md" },
                    themeName = "plain",
                    strings = dev.kuml.desktop.i18n.Strings.EN,
                )
                val r = f.call("kuml_desktop_get_last_error")
                r["source"]!!.jsonPrimitive.content shouldBe "workspace"
                r["path"]!!.jsonPrimitive.content shouldBe "notes/p.md"
            }
        }

        test("info: counts by type, name, trust, open document") {
            withFix { f ->
                val r = f.call("kuml_desktop_workspace_info")
                r["mode"]!!.jsonPrimitive.content shouldBe "knowledge"
                r["documentCount"]!!.jsonPrimitive.int shouldBe 3
                r["trusted"]!!.jsonPrimitive.boolean shouldBe true
                r["countsByType"]!!.jsonObject["UmlClassDiagram"]!!.jsonPrimitive.int shouldBe 1
                r["countsByType"]!!.jsonObject["Concept"]!!.jsonPrimitive.int shouldBe 1
                r["openDocument"] shouldBe kotlinx.serialization.json.JsonNull
            }
            withFix(Fix(trusted = false)) { f ->
                f.call("kuml_desktop_workspace_info")["trusted"]!!.jsonPrimitive.boolean shouldBe false
            }
        }

        test("list: pagination, prefix, limits, revision equals get_document revision, dirty flag") {
            withFix { f ->
                val all = f.call("kuml_desktop_workspace_list_documents")
                all["total"]!!.jsonPrimitive.int shouldBe 3
                val page = f.call("kuml_desktop_workspace_list_documents", args("offset" to 1, "limit" to 1))
                page["documents"]!!.jsonArray.size shouldBe 1
                val pre = f.call("kuml_desktop_workspace_list_documents", args("prefix" to "models/"))
                pre["total"]!!.jsonPrimitive.int shouldBe 1
                val row = pre["documents"]!!.jsonArray[0].jsonObject
                row["kumlBlocks"]!!.jsonPrimitive.int shouldBe 1
                row["type"]!!.jsonPrimitive.content shouldBe "UmlClassDiagram"
                val get = f.call("kuml_desktop_workspace_get_document", args("path" to "models/a.md"))
                row["revision"]!!.jsonPrimitive.content shouldBe get["revision"]!!.jsonPrimitive.content
                row["dirty"]!!.jsonPrimitive.boolean shouldBe false

                f.code("kuml_desktop_workspace_list_documents", args("limit" to 2001)) shouldBe "INVALID_ARGUMENT"
                f.code("kuml_desktop_workspace_list_documents", args("limit" to 0)) shouldBe "INVALID_ARGUMENT"
                f.code("kuml_desktop_workspace_list_documents", args("offset" to -1)) shouldBe "INVALID_ARGUMENT"

                f.ws.select(
                    doc =
                        f.ws.documents.first {
                            it.relativePath == "models/a.md"
                        },
                    themeName = "plain",
                    strings = dev.kuml.desktop.i18n.Strings.EN,
                )
                f.ws.updateBuffer("---\ntype: Concept\n---\nunsaved")
                val dirtyRow =
                    f
                        .call("kuml_desktop_workspace_list_documents", args("prefix" to "models/"))["documents"]!!
                        .jsonArray[0]
                        .jsonObject
                dirtyRow["dirty"]!!.jsonPrimitive.boolean shouldBe true
                dirtyRow["revision"]!!.jsonPrimitive.content shouldBe DocumentRevision.of("---\ntype: Concept\n---\nunsaved")
            }
        }

        test("get: happy path and dirty buffer with diskRevision") {
            withFix { f ->
                val r = f.call("kuml_desktop_workspace_get_document", args("path" to "notes/p.md"))
                r["content"]!!.jsonPrimitive.content shouldBe proseDoc
                r["revision"]!!.jsonPrimitive.content shouldBe DocumentRevision.of(proseDoc)
                r["dirty"]!!.jsonPrimitive.boolean shouldBe false

                f.ws.select(
                    doc = f.ws.documents.first { it.relativePath == "notes/p.md" },
                    themeName = "plain",
                    strings = dev.kuml.desktop.i18n.Strings.EN,
                )
                f.ws.updateBuffer("mine")
                val d = f.call("kuml_desktop_workspace_get_document", args("path" to "notes/p.md"))
                d["content"]!!.jsonPrimitive.content shouldBe "mine"
                d["dirty"]!!.jsonPrimitive.boolean shouldBe true
                d["diskRevision"]!!.jsonPrimitive.content shouldBe DocumentRevision.of(proseDoc)
            }
        }

        test("posixRelativePath equals the parsed relativePath of a nested document on every OS") {
            withFix { f ->
                val doc = f.ws.documents.first { it.relativePath == "notes/p.md" }
                posixRelativePath(root = f.root, file = File(f.root, "notes/p.md")) shouldBe doc.relativePath
            }
        }

        test("get: path escapes are all refused and never leak the root path") {
            withFix { f ->
                val outside = Files.createTempDirectory("kuml-mcp-outside").toFile()
                File(outside, "secret.md").writeText("secret")
                try {
                    try {
                        Files.createSymbolicLink(File(f.root, "linkdir").toPath(), outside.toPath())
                        Files.createSymbolicLink(File(f.root, "linkfile.md").toPath(), File(outside, "secret.md").toPath())
                    } catch (_: Exception) {
                        // symlinks unavailable on this OS/user: the rest still runs
                    }
                    File(f.root, ".git").mkdirs()
                    File(f.root, ".git/x.md").writeText("hidden")
                    File(f.root, "m.kuml.kts").writeText("// script")
                    listOf(
                        "../x.md",
                        "models/../../x.md",
                        "/etc/passwd",
                        "C:/x.md",
                        "\\\\srv\\share\\x.md",
                        "linkdir/secret.md",
                        "linkfile.md",
                        ".git/x.md",
                        "m.kuml.kts",
                        "models",
                    ).forEach { p ->
                        val c = f.code("kuml_desktop_workspace_get_document", args("path" to p))
                        if (p in setOf("linkdir/secret.md", "linkfile.md") && !File(f.root, "linkfile.md").exists()) return@forEach
                        c shouldBe if (c == "DOCUMENT_NOT_FOUND") "DOCUMENT_NOT_FOUND" else "INVALID_PATH"
                    }
                    f.error("kuml_desktop_workspace_get_document", args("path" to "../x.md"))["reason"]!!.jsonPrimitive.content shouldBe
                        "PARENT_SEGMENT"
                } finally {
                    outside.deleteRecursively()
                }
            }
        }

        test("get: unknown document suggests the closest path; oversize is refused") {
            withFix { f ->
                val e = f.error("kuml_desktop_workspace_get_document", args("path" to "models/b.md"))
                e["error"]!!.jsonPrimitive.content shouldBe "DOCUMENT_NOT_FOUND"
                e["suggestion"]!!.jsonPrimitive.content shouldBe "models/a.md"
                File(f.root, "big.md").writeText("---\ntype: Concept\n---\n" + "x".repeat(DesktopMcpWorkspaceTools.MAX_DOCUMENT_BYTES))
                f.code("kuml_desktop_workspace_get_document", args("path" to "big.md")) shouldBe "DOCUMENT_TOO_LARGE"
            }
        }

        test("set: update with the right revision writes, returns the new revision and raises a notice") {
            withFix { f ->
                val r =
                    f.call(
                        "kuml_desktop_workspace_set_document",
                        args("path" to "notes/p.md", "content" to newDoc, "baseRevision" to f.rev("notes/p.md")),
                    )
                r["revision"]!!.jsonPrimitive.content shouldBe DocumentRevision.of(newDoc)
                r["created"]!!.jsonPrimitive.boolean shouldBe false
                f.disk("notes/p.md") shouldBe newDoc
                f.notices shouldContain McpNotice.WorkspaceDocumentWritten(path = "notes/p.md", created = false)
            }
        }

        test("set: a wrong revision is a conflict and the file stays byte-identical") {
            withFix { f ->
                val e =
                    f.error(
                        "kuml_desktop_workspace_set_document",
                        args("path" to "notes/p.md", "content" to newDoc, "baseRevision" to "0000000000000000"),
                    )
                e["error"]!!.jsonPrimitive.content shouldBe "REVISION_CONFLICT"
                e["currentRevision"]!!.jsonPrimitive.content shouldBe DocumentRevision.of(proseDoc)
                f.disk("notes/p.md") shouldBe proseDoc
            }
        }

        test("set tamper: the file changes externally between get and set") {
            withFix { f ->
                val rev = f.call("kuml_desktop_workspace_get_document", args("path" to "notes/p.md"))["revision"]!!.jsonPrimitive.content
                File(f.root, "notes/p.md").writeText("---\ntype: Concept\n---\nexternal edit")
                f.code(
                    "kuml_desktop_workspace_set_document",
                    args("path" to "notes/p.md", "content" to newDoc, "baseRevision" to rev),
                ) shouldBe
                    "REVISION_CONFLICT"
                f.disk("notes/p.md") shouldContain "external edit"
            }
        }

        test("set: create in nested folders appears in the workspace; creating over an existing file conflicts with EXISTS") {
            withFix { f ->
                val r = f.call("kuml_desktop_workspace_set_document", args("path" to "models/sub/new.md", "content" to newDoc))
                r["created"]!!.jsonPrimitive.boolean shouldBe true
                f.disk("models/sub/new.md") shouldBe newDoc
                f.ws.documents.map { it.relativePath } shouldContain "models/sub/new.md"
                f.notices shouldContain McpNotice.WorkspaceDocumentWritten(path = "models/sub/new.md", created = true)

                val e = f.error("kuml_desktop_workspace_set_document", args("path" to "models/sub/new.md", "content" to proseDoc))
                e["error"]!!.jsonPrimitive.content shouldBe "REVISION_CONFLICT"
                e["reason"]!!.jsonPrimitive.content shouldBe "EXISTS"
                e["currentRevision"]!!.jsonPrimitive.content shouldBe DocumentRevision.of(newDoc)
                f.disk("models/sub/new.md") shouldBe newDoc
            }
        }

        test("set: a document with unsaved editor changes is never overwritten, even with a matching revision") {
            withFix { f ->
                f.ws.select(
                    doc = f.ws.documents.first { it.relativePath == "notes/p.md" },
                    themeName = "plain",
                    strings = dev.kuml.desktop.i18n.Strings.EN,
                )
                f.ws.updateBuffer("---\ntype: Concept\n---\nmine")
                f.code(
                    "kuml_desktop_workspace_set_document",
                    args("path" to "notes/p.md", "content" to newDoc, "baseRevision" to f.rev("notes/p.md")),
                ) shouldBe "DOCUMENT_DIRTY"
                f.disk("notes/p.md") shouldBe proseDoc
                f.ws.buffer!! shouldContain "mine"
            }
        }

        test("set: the save gate blocks an unknown type and creates nothing, not even folders") {
            withFix { f ->
                val e =
                    f.error(
                        "kuml_desktop_workspace_set_document",
                        args("path" to "fresh/dir/x.md", "content" to "---\ntype: Quatsch\n---\nx"),
                    )
                e["error"]!!.jsonPrimitive.content shouldBe "SAVE_BLOCKED"
                (e["findings"] as JsonArray).size shouldBe 1
                File(f.root, "fresh").exists() shouldBe false
            }
        }

        test("set: size, extension and path escapes are refused") {
            withFix { f ->
                f.code(
                    "kuml_desktop_workspace_set_document",
                    args("path" to "x.md", "content" to "a".repeat(DesktopMcpWorkspaceTools.MAX_DOCUMENT_BYTES + 1)),
                ) shouldBe "CONTENT_TOO_LARGE"
                f.code("kuml_desktop_workspace_set_document", args("path" to "x.txt", "content" to newDoc)) shouldBe "INVALID_PATH"
                f.code("kuml_desktop_workspace_set_document", args("path" to "../x.md", "content" to newDoc)) shouldBe "INVALID_PATH"
                f.code("kuml_desktop_workspace_set_document", args("path" to ".hidden/x.md", "content" to newDoc)) shouldBe "INVALID_PATH"
                f.code("kuml_desktop_workspace_set_document", args("path" to "CON.md", "content" to newDoc)) shouldBe "INVALID_PATH"
                File(f.root.parentFile, "x.md").exists() shouldBe false
            }
        }

        test("set: a symlinked folder is refused and nothing lands outside the root") {
            withFix { f ->
                val outside = Files.createTempDirectory("kuml-mcp-outside").toFile()
                try {
                    val linked =
                        try {
                            Files.createSymbolicLink(File(f.root, "link").toPath(), outside.toPath())
                            true
                        } catch (_: Exception) {
                            false
                        }
                    if (linked) {
                        f.code("kuml_desktop_workspace_set_document", args("path" to "link/x.md", "content" to newDoc)) shouldBe
                            "INVALID_PATH"
                        File(outside, "x.md").exists() shouldBe false
                    }
                } finally {
                    outside.deleteRecursively()
                }
            }
        }

        test("set and delete need a trusted workspace; the file stays unchanged") {
            withFix(Fix(trusted = false)) { f ->
                f.code(
                    "kuml_desktop_workspace_set_document",
                    args(
                        "path" to "notes/p.md",
                        "content" to newDoc,
                        "baseRevision" to f.rev("notes/p.md"),
                    ),
                ) shouldBe
                    "WORKSPACE_NOT_TRUSTED"
                f.code("kuml_desktop_workspace_delete_document", args("path" to "notes/p.md", "confirm" to true)) shouldBe
                    "WORKSPACE_NOT_TRUSTED"
                f.disk("notes/p.md") shouldBe proseDoc
                f.code("kuml_desktop_workspace_render_document", args("path" to "models/a.md")) shouldBe "WORKSPACE_NOT_TRUSTED"
                f.code("kuml_desktop_workspace_render_all") shouldBe "WORKSPACE_NOT_TRUSTED"
                f.code("kuml_desktop_workspace_validate", args("evaluate" to true)) shouldBe "WORKSPACE_NOT_TRUSTED"
                f.call("kuml_desktop_workspace_get_document", args("path" to "notes/p.md"))["content"]!!.jsonPrimitive.content shouldBe
                    proseDoc
                f.call("kuml_desktop_workspace_validate")["scope"]!!.jsonPrimitive.content shouldBe "workspace"
            }
        }

        test("delete: needs confirm exactly true; deletes and updates the document list") {
            withFix { f ->
                listOf(
                    args("path" to "notes/p.md"),
                    args("path" to "notes/p.md", "confirm" to false),
                    args(
                        "path" to "notes/p.md",
                        "confirm" to "true",
                    ),
                ).forEach { f.code("kuml_desktop_workspace_delete_document", it) shouldBe "CONFIRMATION_REQUIRED" }
                f.disk("notes/p.md") shouldBe proseDoc

                f
                    .call("kuml_desktop_workspace_delete_document", args("path" to "notes/p.md", "confirm" to true))["deleted"]!!
                    .jsonPrimitive.content shouldBe "notes/p.md"
                File(f.root, "notes/p.md").exists() shouldBe false
                f.ws.documents.map { it.relativePath } shouldNotContain "notes/p.md"
                f.notices shouldContain McpNotice.WorkspaceDocumentDeleted(path = "notes/p.md")
            }
        }

        test("delete: dirty, wrong revision, escapes and symlinks are refused") {
            withFix { f ->
                f.code(
                    "kuml_desktop_workspace_delete_document",
                    args(
                        "path" to "notes/p.md",
                        "confirm" to true,
                        "baseRevision" to "1111111111111111",
                    ),
                ) shouldBe
                    "REVISION_CONFLICT"
                f.code("kuml_desktop_workspace_delete_document", args("path" to "../x.md", "confirm" to true)) shouldBe "INVALID_PATH"
                f.code("kuml_desktop_workspace_delete_document", args("path" to "missing.md", "confirm" to true)) shouldBe
                    "DOCUMENT_NOT_FOUND"
                try {
                    Files.createSymbolicLink(File(f.root, "ln.md").toPath(), File(f.root, "notes/p.md").toPath())
                    f.code("kuml_desktop_workspace_delete_document", args("path" to "ln.md", "confirm" to true)) shouldBe "INVALID_PATH"
                } catch (_: UnsupportedOperationException) {
                } catch (_: java.io.IOException) {
                }
                f.ws.select(
                    doc = f.ws.documents.first { it.relativePath == "notes/p.md" },
                    themeName = "plain",
                    strings = dev.kuml.desktop.i18n.Strings.EN,
                )
                f.ws.updateBuffer("changed")
                f.code("kuml_desktop_workspace_delete_document", args("path" to "notes/p.md", "confirm" to true)) shouldBe "DOCUMENT_DIRTY"
                f.disk("notes/p.md") shouldBe proseDoc
            }
        }

        test("validate: structured findings, scoped document, evaluate only on request") {
            withFix { f ->
                File(f.root, "bad.md").writeText("no frontmatter at all\n")
                val r = f.call("kuml_desktop_workspace_validate")
                r["ok"]!!.jsonPrimitive.boolean shouldBe false
                val first = r["findings"]!!.jsonArray.map { it.jsonObject }.first { it["path"]!!.jsonPrimitive.content == "bad.md" }
                listOf("code", "severity", "path", "line", "message").forEach { first.containsKey(it) shouldBe true }
                f.evaluateCalls shouldBe 0

                val one = f.call("kuml_desktop_workspace_validate", args("path" to "models/a.md"))
                one["scope"]!!.jsonPrimitive.content shouldBe "document"
                one["ok"]!!.jsonPrimitive.boolean shouldBe true

                val ev = f.call("kuml_desktop_workspace_validate", args("path" to "models/a.md", "evaluate" to true))
                f.evaluateCalls shouldBe 1
                ev["ok"]!!.jsonPrimitive.boolean shouldBe false
                ev["errorCount"]!!.jsonPrimitive.int shouldBe 1

                f.ws.select(
                    doc =
                        f.ws.documents.first {
                            it.relativePath == "models/a.md"
                        },
                    themeName = "plain",
                    strings = dev.kuml.desktop.i18n.Strings.EN,
                )
                f.ws.updateBuffer("dirty")
                f
                    .call("kuml_desktop_workspace_validate", args("path" to "models/a.md"))["unsavedDocuments"]!!
                    .jsonArray[0]
                    .jsonPrimitive.content shouldBe "models/a.md"
            }
        }

        test("validate with evaluate answers BUSY while the eval slot is taken") {
            withFix(Fix(gateWaitMs = 100)) { f ->
                f.tools.evalGate.acquire()
                try {
                    f.code("kuml_desktop_workspace_validate", args("evaluate" to true)) shouldBe "BUSY"
                } finally {
                    f.tools.evalGate.release()
                }
            }
        }

        test("render_document: svg, png as image content, argument errors, temp dir cleanup") {
            withFix { f ->
                val svg = f.call("kuml_desktop_workspace_render_document", args("path" to "models/a.md"))
                svg["ok"]!!.jsonPrimitive.boolean shouldBe true
                svg["svg"]!!.jsonPrimitive.content shouldStartWith "<svg"

                val c =
                    f.contents(
                        "kuml_desktop_workspace_render_document",
                        args("path" to "models/a.md", "format" to "png", "width" to 800),
                    )
                c.size shouldBe 2
                c[1].type shouldBe "image"
                c[1].mimeType shouldBe "image/png"
                Base64.getDecoder().decode(c[1].data!!).take(4) shouldBe png.take(4)

                f.code("kuml_desktop_workspace_render_document", args("path" to "models/a.md", "blockIndex" to 3)) shouldBe
                    "BLOCK_NOT_FOUND"
                f.code("kuml_desktop_workspace_render_document", args("path" to "models/a.md", "theme" to "no-such-theme")) shouldBe
                    "UNKNOWN_THEME"
                f.code("kuml_desktop_workspace_render_document", args("path" to "models/a.md", "format" to "gif")) shouldBe
                    "INVALID_ARGUMENT"
                f.code("kuml_desktop_workspace_render_document", args("path" to "models/a.md", "width" to 100000)) shouldBe
                    "INVALID_ARGUMENT"
                f.renderTmpDirs.isNotEmpty() shouldBe true
                f.renderTmpDirs.none { it.exists() } shouldBe true
            }
        }

        test("render_document: a render that never finishes yields RENDER_TIMEOUT") {
            val latch = CountDownLatch(1)
            withFix(Fix(renderTimeoutMs = 200, blockRender = latch)) { f ->
                try {
                    f.code("kuml_desktop_workspace_render_document", args("path" to "models/a.md")) shouldBe "RENDER_TIMEOUT"
                } finally {
                    latch.countDown()
                }
            }
        }

        test("render_all: counts rendered and skipped blocks and cleans up") {
            withFix { f ->
                val r = f.call("kuml_desktop_workspace_render_all")
                r["ok"]!!.jsonPrimitive.boolean shouldBe true
                r["renderedCount"]!!.jsonPrimitive.int shouldBe 1
                r["skippedCount"]!!.jsonPrimitive.int shouldBe 2
                r["rendered"]!!
                    .jsonArray[0]
                    .jsonObject["path"]!!
                    .jsonPrimitive.content shouldBe "models/a.md"
                f.renderTmpDirs.none { it.exists() } shouldBe true
            }
        }

        test("render_document with the real renderer produces a real svg") {
            withFix { f ->
                val real =
                    DesktopMcpTools(
                        state = f.state,
                        render = McpRenderPort { throw IllegalStateException() },
                        validate = McpValidatePort { throw IllegalStateException() },
                        editorActions = { null },
                        onNotice = {},
                        uiDispatcher = Dispatchers.Unconfined,
                        evalDispatcher = Dispatchers.Unconfined,
                    )
                val tool = real.tools.first { it.descriptor.name == "kuml_desktop_workspace_render_document" }
                val out = Json.parseToJsonElement(tool.call(args("path" to "models/a.md"))[0].text!!).jsonObject
                out["ok"]!!.jsonPrimitive.boolean shouldBe true
                out["svg"]!!.jsonPrimitive.content shouldContain "<svg"
                out["svg"]!!.jsonPrimitive.content shouldContain "Fahrzeug"
            }
        }

        test("open_document selects the document, honours edit and refuses to discard another dirty one") {
            withFix { f ->
                val r = f.call("kuml_desktop_workspace_open_document", args("path" to "models/a.md", "edit" to true))
                r["path"]!!.jsonPrimitive.content shouldBe "models/a.md"
                f.ws.selected!!.relativePath shouldBe "models/a.md"
                f.ws.editing shouldBe true

                f.ws.updateBuffer("---\ntype: Concept\n---\ndirty")
                f.code("kuml_desktop_workspace_open_document", args("path" to "notes/p.md")) shouldBe "DOCUMENT_DIRTY"
                f.ws.selected!!.relativePath shouldBe "models/a.md"
                f.ws.buffer!! shouldContain "dirty"
            }
        }

        test("open_document picks up a document that was created externally") {
            withFix { f ->
                File(f.root, "ext.md").writeText(newDoc)
                f.call("kuml_desktop_workspace_open_document", args("path" to "ext.md"))
                f.ws.selected!!.relativePath shouldBe "ext.md"
            }
        }

        test("reload: fail on dirty, keep keeps the buffer and reports added/removed, discard drops it") {
            withFix { f ->
                f.ws.select(
                    doc = f.ws.documents.first { it.relativePath == "notes/p.md" },
                    themeName = "plain",
                    strings = dev.kuml.desktop.i18n.Strings.EN,
                )
                f.ws.updateBuffer("mine")
                File(f.root, "added.md").writeText(newDoc)
                File(f.root, "models/a.md").delete()

                f.code("kuml_desktop_workspace_reload") shouldBe "DOCUMENT_DIRTY"
                f.ws.buffer shouldBe "mine"

                val keep = f.call("kuml_desktop_workspace_reload", args("onDirty" to "keep"))
                keep["added"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("added.md")
                keep["removed"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("models/a.md")
                keep["dirty"]!!.jsonPrimitive.boolean shouldBe true
                f.ws.buffer shouldBe "mine"

                val discard = f.call("kuml_desktop_workspace_reload", args("onDirty" to "discard"))
                discard["discarded"]!!.jsonPrimitive.boolean shouldBe true
                f.ws.buffer shouldBe proseDoc
                f.ws.isDirty shouldBe false
                f.code("kuml_desktop_workspace_reload", args("onDirty" to "wat")) shouldBe "INVALID_ARGUMENT"
            }
        }

        test("a blocked UI thread yields UI_BUSY") {
            val executor = Executors.newSingleThreadExecutor()
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            executor.submit {
                held.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            held.await()
            val f = Fix(uiDispatcher = executor.asCoroutineDispatcher(), uiTimeoutMs = 200)
            try {
                f.code("kuml_desktop_workspace_info") shouldBe "UI_BUSY"
                f.code("kuml_desktop_workspace_set_document", args("path" to "x.md", "content" to newDoc)) shouldBe "UI_BUSY"
                File(f.root, "x.md").exists() shouldBe false
            } finally {
                release.countDown()
                executor.shutdown()
                f.cleanup()
            }
        }

        test("logging hygiene: no content, no token, no absolute root path in MCP log events") {
            val logger = LoggerFactory.getLogger("dev.kuml.desktop.mcp") as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            val previous = logger.level
            logger.level = Level.ALL
            logger.addAppender(appender)
            try {
                withFix { f ->
                    val secret = "---\ntype: Concept\n---\nSECRET-CONTENT-123\n"
                    f.call("kuml_desktop_workspace_set_document", args("path" to "secret-path-x.md", "content" to secret))
                    f.error("kuml_desktop_workspace_set_document", args("path" to "../secret-path-y.md", "content" to secret))
                    f.error("kuml_desktop_workspace_get_document", args("path" to "nope-z.md"))
                    val logged = appender.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
                    logged shouldNotContain "SECRET-CONTENT-123"
                    logged shouldNotContain f.root.path
                    logged shouldNotContain f.root.canonicalPath
                    logged shouldNotContain "Bearer"
                }
            } finally {
                logger.detachAppender(appender)
                logger.level = previous
            }
        }
    })
