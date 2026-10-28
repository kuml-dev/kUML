package dev.kuml.desktop.mcp

import dev.kuml.desktop.AppState
import dev.kuml.desktop.render.DesktopEngineInit
import dev.kuml.desktop.workspace.OpenWorkspace
import dev.kuml.desktop.workspace.WorkspaceState
import dev.kuml.desktop.workspace.WorkspaceTrust
import dev.kuml.mcp.tools.McpToolException
import dev.kuml.workspace.OkfFinding
import dev.kuml.workspace.OkfSeverity
import dev.kuml.workspace.WorkspaceScanner
import dev.kuml.workspace.pdf.PdfExportException
import dev.kuml.workspace.pdf.PdfExportKind
import dev.kuml.workspace.pdf.PdfExportOptions
import dev.kuml.workspace.pdf.PdfExportResult
import dev.kuml.workspace.pdf.ScaleWarning
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Semaphore

class DesktopMcpWorkspacePdfExportTest :
    FunSpec({
        System.setProperty("java.awt.headless", "true")
        DesktopEngineInit.ensure()

        class Fix(
            trusted: Boolean = true,
            engineering: Boolean = false,
            private val behaviour: (PdfExportOptions, File, Boolean, Boolean) -> PdfExportResult = { _, target, _, _ ->
                PdfExportResult(
                    files = listOf(target),
                    pageCount = 3,
                    documentCount = 2,
                    diagramCount = 1,
                    warnings = emptyList(),
                    failures = emptyList(),
                )
            },
            val gate: Semaphore = Semaphore(1),
        ) {
            val root: File = Files.createTempDirectory("kuml-mcp-pdf").toFile()
            var calls = 0
            var lastOptions: PdfExportOptions? = null
            var lastRestrict: File? = null

            init {
                File(root, "index.md").writeText("---\ntype: KumlWorkspace\n---\n# Index\n")
                File(root, "a.md").writeText("---\ntype: Concept\n---\n# A\n")
            }

            val state = AppState()

            init {
                val ws = WorkspaceState(initialWorkspace = WorkspaceScanner.scan(root = root), ioDispatcher = Dispatchers.Unconfined)
                state.openWorkspace =
                    if (engineering) OpenWorkspace.Engineering(root = root, scriptFiles = emptyList()) else OpenWorkspace.Knowledge(ws)
                if (trusted) state.trustedWorkspaces.add(WorkspaceTrust.canonicalPath(root))
            }

            val tools =
                DesktopMcpTools(
                    state = state,
                    render = McpRenderPort { throw IllegalStateException("must not run") },
                    validate = McpValidatePort { throw IllegalStateException("must not run") },
                    editorActions = { null },
                    onNotice = {},
                    uiDispatcher = Dispatchers.Unconfined,
                    evalDispatcher = Dispatchers.IO,
                    gateWaitMs = 300,
                    evalGate = gate,
                    workspacePdfExport =
                        McpWorkspacePdfExportPort { _, options, target, overwrite, restrictToRoot, _, strict, _ ->
                            calls++
                            lastOptions = options
                            lastRestrict = restrictToRoot
                            behaviour(options, target, overwrite, strict)
                        },
                )

            fun call(args: JsonObject = JsonObject(emptyMap())): JsonObject =
                Json
                    .parseToJsonElement(
                        tools.tools
                            .first { it.descriptor.name == "kuml_desktop_workspace_export_pdf" }
                            .call(args)[0]
                            .text!!,
                    ).jsonObject

            fun error(args: JsonObject = JsonObject(emptyMap())): JsonObject {
                val e =
                    shouldThrow<McpToolException> {
                        tools.tools.first { it.descriptor.name == "kuml_desktop_workspace_export_pdf" }.call(
                            args,
                        )
                    }
                e.message!! shouldNotContain root.path
                return Json.parseToJsonElement(e.message!!).jsonObject
            }

            fun code(args: JsonObject = JsonObject(emptyMap())) = error(args)["error"]!!.jsonPrimitive.content
        }

        fun args(vararg pairs: Pair<String, Any>) =
            buildJsonObject {
                pairs.forEach { (k, v) ->
                    when (v) {
                        is Boolean -> put(k, v)
                        is Int -> put(k, v)
                        is List<*> -> put(k, buildJsonArray { v.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.toString())) } })
                        else -> put(k, v.toString())
                    }
                }
            }

        test("success returns counts, relative output and never content") {
            val f = Fix()
            val r = f.call(args("output" to "out/book.pdf", "page" to "a3", "draft" to true))
            r["ok"]!!.jsonPrimitive.boolean shouldBe true
            r["output"]!!.jsonPrimitive.content shouldBe "out/book.pdf"
            r["pageCount"]!!.jsonPrimitive.int shouldBe 3
            r["files"]!!.jsonArray[0].jsonPrimitive.content shouldBe "out/book.pdf"
            f.lastOptions!!.kind shouldBe PdfExportKind.HANDBOOK
            f.lastOptions!!.draft shouldBe true
            f.lastRestrict!!.canonicalPath shouldBe f.root.canonicalPath
            f.root.deleteRecursively()
        }

        test("default output is below .kuml-out/export and per-document uses a folder") {
            val f = Fix()
            f
                .call()["output"]!!
                .jsonPrimitive.content
                .startsWith(".kuml-out/export/") shouldBe true
            f.call(args("kind" to "per-document"))["output"]!!.jsonPrimitive.content shouldBe ".kuml-out/export/pdf"
            f.root.deleteRecursively()
        }

        test("untrusted workspace: WORKSPACE_NOT_TRUSTED, nothing runs") {
            val f = Fix(trusted = false)
            f.code() shouldBe "WORKSPACE_NOT_TRUSTED"
            f.calls shouldBe 0
            f.root.deleteRecursively()
        }

        test("engineering workspace: WORKSPACE_MODE_UNSUPPORTED") {
            val f = Fix(engineering = true)
            f.code(args("output" to "x.pdf")) shouldBe "WORKSPACE_MODE_UNSUPPORTED"
            f.root.deleteRecursively()
        }

        test("path escapes are rejected as INVALID_PATH before anything runs") {
            val f = Fix()
            listOf("../x.pdf", "/etc/x.pdf", "a/../../x.pdf").forEach { f.code(args("output" to it)) shouldBe "INVALID_PATH" }
            f.code(args("scope" to listOf("../x.md"))) shouldBe "INVALID_PATH"
            f.code(args("folder" to "../x")) shouldBe "INVALID_PATH"
            f.calls shouldBe 0
            f.root.deleteRecursively()
        }

        test("invalid arguments and mixed scope") {
            val f = Fix()
            f.code(args("kind" to "zip")) shouldBe "INVALID_ARGUMENT"
            f.code(args("date" to "yesterday")) shouldBe "INVALID_ARGUMENT"
            f.code(args("split" to true)) shouldBe "INVALID_ARGUMENT"
            f.code(args("scope" to listOf("a.md", "folder:x"))) shouldBe "INVALID_ARGUMENT"
            f.root.deleteRecursively()
        }

        test("export exceptions map to stable error codes") {
            fun codeFor(c: PdfExportException.Code): String {
                val f = Fix(behaviour = { _, _, _, _ -> throw PdfExportException(code = c, message = "x") })
                return f.code(args("output" to "x.pdf")).also { f.root.deleteRecursively() }
            }
            codeFor(PdfExportException.Code.OUTPUT_EXISTS) shouldBe "OUTPUT_EXISTS"
            codeFor(PdfExportException.Code.LIMIT_PAGES) shouldBe "PAGE_LIMIT"
            codeFor(PdfExportException.Code.LIMIT_OUTPUT_SIZE) shouldBe "OUTPUT_TOO_LARGE"
            codeFor(PdfExportException.Code.EMPTY_SCOPE) shouldBe "EMPTY_SCOPE"
            codeFor(PdfExportException.Code.TIMEOUT) shouldBe "EXPORT_TIMEOUT"
            codeFor(PdfExportException.Code.PATH_OUTSIDE_ROOT) shouldBe "INVALID_PATH"
        }

        test("strict export with a too-small diagram answers SCALE_TOO_SMALL") {
            val f =
                Fix(behaviour = { _, _, _, strict ->
                    if (strict) {
                        throw PdfStrictViolation(
                            warnings =
                                listOf(
                                    ScaleWarning(
                                        documentPath = "a.md",
                                        blockIndex = 0,
                                        title = "t",
                                        scale = 0.2,
                                        suggestion = "A3 landscape",
                                    ),
                                ),
                            failures =
                                listOf(
                                    OkfFinding(code = "OKF-E-007", severity = OkfSeverity.ERROR, file = "a.md", line = 3, message = "m"),
                                ),
                        )
                    }
                    PdfExportResult(
                        files = emptyList(),
                        pageCount = 0,
                        documentCount = 0,
                        diagramCount = 0,
                        warnings = emptyList(),
                        failures = emptyList(),
                    )
                })
            val e = f.error(args("strict" to true, "output" to "x.pdf"))
            e["error"]!!.jsonPrimitive.content shouldBe "SCALE_TOO_SMALL"
            e["warnings"]!!.jsonArray.size shouldBe 1
            f.root.deleteRecursively()
        }

        test("the shared evaluation slot is respected: a busy gate answers BUSY") {
            val gate = Semaphore(1).also { it.acquire() }
            val f = Fix(gate = gate)
            f.code(args("output" to "x.pdf")) shouldBe "BUSY"
            f.calls shouldBe 0
            f.root.deleteRecursively()
        }

        test("document and diagram limits are enforced before running") {
            val f = Fix()
            repeat(
                DesktopMcpWorkspaceTools.MAX_EXPORT_DOCUMENTS + 1,
            ) { File(f.root, "d$it.md").writeText("---\ntype: Concept\n---\n# D\n") }
            f.code(args("output" to "x.pdf")) shouldBe "TOO_MANY_DOCUMENTS"
            f.calls shouldBe 0
            f.root.deleteRecursively()
        }
    })
