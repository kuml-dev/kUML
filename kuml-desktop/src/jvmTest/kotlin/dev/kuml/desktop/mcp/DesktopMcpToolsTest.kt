package dev.kuml.desktop.mcp

import androidx.compose.runtime.mutableStateOf
import dev.kuml.desktop.AppState
import dev.kuml.desktop.editor.EditorActions
import dev.kuml.desktop.render.DesktopDiagnostic
import dev.kuml.desktop.render.DesktopEngineInit
import dev.kuml.desktop.render.DesktopRenderResult
import dev.kuml.desktop.render.DesktopValidationResult
import dev.kuml.desktop.workspace.OpenWorkspace
import dev.kuml.mcp.tools.McpToolException
import dev.kuml.renderer.theme.core.ThemeRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class DesktopMcpToolsTest :
    FunSpec({
        System.setProperty("java.awt.headless", "true")
        DesktopEngineInit.ensure()

        class Fixture(
            val state: AppState = AppState(),
            var renderResult: Deferred<DesktopRenderResult?> = CompletableDeferred(DesktopRenderResult.Svg(svg = "<svg/>")),
            var validateResult: DesktopValidationResult = DesktopValidationResult(ok = true, diagnostics = emptyList()),
            var actions: EditorActions? = null,
            val notices: MutableList<McpNotice> = mutableListOf(),
            val renderTimeoutMs: Long = 30_000,
        ) {
            init {
                DesktopEngineInit.ensure()
            }

            var validateCalls = 0
            val tools =
                DesktopMcpTools(
                    state = state,
                    render = McpRenderPort { renderResult },
                    validate =
                        McpValidatePort {
                            validateCalls++
                            validateResult
                        },
                    editorActions = { actions },
                    onNotice = { notices += it },
                    uiDispatcher = Dispatchers.Unconfined,
                    evalDispatcher = Dispatchers.Unconfined,
                    renderTimeoutMs = renderTimeoutMs,
                )

            fun call(
                name: String,
                args: JsonObject = JsonObject(emptyMap()),
            ): JsonObject {
                val tool = tools.tools.first { it.descriptor.name == name }
                return Json.parseToJsonElement(tool.call(args)[0].text!!).jsonObject
            }

            fun error(
                name: String,
                args: JsonObject = JsonObject(emptyMap()),
            ): JsonObject {
                val e = shouldThrow<McpToolException> { call(name, args) }
                return Json.parseToJsonElement(e.message!!).jsonObject
            }

            fun revision() = call("kuml_desktop_get_script")["revision"]!!.jsonPrimitive.content
        }

        fun set(
            script: String,
            base: String,
        ) = buildJsonObject {
            put("script", script)
            put("baseRevision", base)
        }

        test("exposes the seven script tools plus the ten workspace tools with annotations") {
            val f = Fixture()
            f.tools.tools.map { it.descriptor.name } shouldBe
                listOf(
                    "kuml_desktop_get_script",
                    "kuml_desktop_set_script",
                    "kuml_desktop_render",
                    "kuml_desktop_validate",
                    "kuml_desktop_get_last_error",
                    "kuml_desktop_get_theme",
                    "kuml_desktop_set_theme",
                ) + DesktopMcpWorkspaceTools.TOOL_NAMES
            f.tools.tools.size shouldBe 17
            f.tools.tools.associate { it.descriptor.name to it.descriptor.annotations!! }.let { a ->
                a["kuml_desktop_get_script"]!!.readOnlyHint shouldBe true
                a["kuml_desktop_set_script"]!!.destructiveHint shouldBe true
                a["kuml_desktop_render"]!!.idempotentHint shouldBe true
                a["kuml_desktop_set_theme"]!!.readOnlyHint shouldBe false
            }
        }

        test("registry combines live tools with the four authoring tools") {
            val f = Fixture()
            desktopMcpRegistry(tools = f.tools).descriptors.map { it.name } shouldBe
                f.tools.tools.map { it.descriptor.name } +
                listOf("kuml.describe", "kuml.examples", "kuml.generate", "kuml.list_elements")
        }

        test("get_script returns script, revision, dirty flag and only the file NAME") {
            val f = Fixture()
            f.state.loadFrom(file = java.io.File("/some/secret/dir/model.kuml.kts"), content = "abc")
            val r = f.call("kuml_desktop_get_script")
            r["script"]!!.jsonPrimitive.content shouldBe "abc"
            r["revision"]!!.jsonPrimitive.content shouldBe DesktopMcpTools.revisionOf("abc")
            r["dirty"]!!.jsonPrimitive.content shouldBe "false"
            r["file"]!!.jsonPrimitive.content shouldBe "model.kuml.kts"
            r.toString().contains("secret") shouldBe false
        }

        test("get_script has a null file for an unsaved buffer") {
            Fixture().call("kuml_desktop_get_script")["file"] shouldBe JsonNull
        }

        test("set_script with matching revision replaces the buffer, marks dirty, and saves nothing") {
            val f = Fixture()
            f.state.loadFrom(file = java.io.File("/tmp/x.kuml.kts"), content = "old")
            val r = f.call("kuml_desktop_set_script", set("new", DesktopMcpTools.revisionOf("old")))
            r["revision"]!!.jsonPrimitive.content shouldBe DesktopMcpTools.revisionOf("new")
            f.state.script shouldBe "new"
            f.state.isDirty shouldBe true
            f.state.currentFile shouldBe java.io.File("/tmp/x.kuml.kts")
            f.state.discardScriptChanges()
            f.state.script shouldBe "old" // savedScript baseline untouched by the MCP edit
            f.notices.size shouldBe 1
        }

        test("set_script with a stale revision yields CONFLICT with the current revision and leaves the buffer") {
            val f = Fixture()
            f.state.script = "mine"
            val e = f.error("kuml_desktop_set_script", set("theirs", "deadbeefdeadbeef"))
            e["error"]!!.jsonPrimitive.content shouldBe "CONFLICT"
            e["currentRevision"]!!.jsonPrimitive.content shouldBe DesktopMcpTools.revisionOf("mine")
            f.state.script shouldBe "mine"
            f.notices.size shouldBe 0
        }

        test("set_script is refused while a simulation is starting") {
            val f = Fixture()
            f.state.simulationStarting = true
            f.error("kuml_desktop_set_script", set("x", f.revision()))["error"]!!.jsonPrimitive.content shouldBe "SIMULATION_RUNNING"
            f.state.script shouldBe AppState.WELCOME_SCRIPT
        }

        test("workspace mode: script tools refuse, theme tools keep working") {
            val f = Fixture()
            f.state.openWorkspace = OpenWorkspace.Engineering(root = java.io.File("/tmp"), scriptFiles = emptyList())
            f.error("kuml_desktop_get_script")["error"]!!.jsonPrimitive.content shouldBe "WORKSPACE_MODE_UNSUPPORTED"
            f.error("kuml_desktop_set_script", set("x", "y"))["error"]!!.jsonPrimitive.content shouldBe "WORKSPACE_MODE_UNSUPPORTED"
            f.error("kuml_desktop_render")["error"]!!.jsonPrimitive.content shouldBe "WORKSPACE_MODE_UNSUPPORTED"
            val refusal = f.error("kuml_desktop_get_script")
            refusal["mode"]!!.jsonPrimitive.content shouldBe "engineering"
            refusal["tools"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe DesktopMcpWorkspaceTools.TOOL_NAMES
            f.call("kuml_desktop_get_theme")["theme"]!!.jsonPrimitive.content shouldBe f.state.theme
            val other = ThemeRegistry.names().first { it != f.state.theme }
            f.call("kuml_desktop_set_theme", buildJsonObject { put("theme", other) })
            f.state.theme shouldBe other
        }

        test("set_script rejects scripts above 512 KiB") {
            val f = Fixture()
            val big = "x".repeat(DesktopMcpTools.MAX_SCRIPT_BYTES + 1)
            f.error("kuml_desktop_set_script", set(big, f.revision()))["error"]!!.jsonPrimitive.content shouldBe "SCRIPT_TOO_LARGE"
            f.state.script shouldBe AppState.WELCOME_SCRIPT
        }

        test("set_script rejects missing or non-string arguments") {
            val f = Fixture()
            f.error("kuml_desktop_set_script", buildJsonObject { put("script", "x") })["error"]!!.jsonPrimitive.content shouldBe
                "INVALID_ARGUMENT"
            f
                .error(
                    "kuml_desktop_set_script",
                    buildJsonObject {
                        put("script", 5)
                        put("baseRevision", "r")
                    },
                )["error"]!!
                .jsonPrimitive.content shouldBe "INVALID_ARGUMENT"
        }

        test("with a mounted editor, set_script goes through replaceAllUndoable and still notifies") {
            val f = Fixture()
            var undoableCall: String? = null
            f.actions =
                EditorActions(
                    undo = {},
                    redo = {},
                    canUndo = mutableStateOf(false),
                    canRedo = mutableStateOf(false),
                    beginFind = {},
                    find = { _, _, _, _ -> false },
                    endFind = {},
                    replaceAllUndoable = { text ->
                        undoableCall = text
                        // what the DocumentListener does for a real edit
                        f.state.isDirty = true
                        f.state.script = text
                    },
                )
            f.call("kuml_desktop_set_script", set("viaEditor", f.revision()))
            undoableCall shouldBe "viaEditor"
            f.state.script shouldBe "viaEditor"
            f.notices.single().shouldBe(
                McpNotice.ScriptReplaced(previousScript = AppState.WELCOME_SCRIPT, newRevision = DesktopMcpTools.revisionOf("viaEditor")),
            )
        }

        test("in diagram-only view (no editor) set_script writes the state directly") {
            val f = Fixture()
            f.state.viewMode = AppState.ViewMode.DIAGRAM
            var editorTouched = false
            f.actions =
                EditorActions(
                    undo = {},
                    redo = {},
                    canUndo = mutableStateOf(false),
                    canRedo = mutableStateOf(false),
                    beginFind = {},
                    find = { _, _, _, _ -> false },
                    endFind = {},
                    replaceAllUndoable = { editorTouched = true },
                )
            f.call("kuml_desktop_set_script", set("direct", f.revision()))
            editorTouched shouldBe false
            f.state.script shouldBe "direct"
            f.state.isDirty shouldBe true
            f.notices.size shouldBe 1
        }

        test("set_theme with a valid theme changes state and persisted settings") {
            val f = Fixture()
            val other = ThemeRegistry.names().first { it != f.state.theme }
            f.call("kuml_desktop_set_theme", buildJsonObject { put("theme", other) })["theme"]!!.jsonPrimitive.content shouldBe other
            f.state.theme shouldBe other
            f.state.toSettings().theme shouldBe other
        }

        test("set_theme with an unknown theme yields UNKNOWN_THEME and keeps state") {
            val f = Fixture()
            val before = f.state.theme
            f
                .error("kuml_desktop_set_theme", buildJsonObject { put("theme", "no-such-theme") })["error"]!!
                .jsonPrimitive.content shouldBe "UNKNOWN_THEME"
            f.state.theme shouldBe before
        }

        test("get_theme lists available themes") {
            val f = Fixture()
            val r = f.call("kuml_desktop_get_theme")
            r["available"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe ThemeRegistry.names().toList()
        }

        test("get_last_error returns null or the message") {
            val f = Fixture()
            f.call("kuml_desktop_get_last_error")["error"] shouldBe JsonNull
            f.state.lastError = "boom"
            f.call("kuml_desktop_get_last_error")["error"]!!.jsonPrimitive.content shouldBe "boom"
        }

        test("validate returns diagnostics and does not change any state, with or without an explicit script") {
            val f = Fixture()
            f.validateResult =
                DesktopValidationResult(
                    ok = false,
                    diagnostics = listOf(DesktopDiagnostic(severity = "ERROR", message = "bad", line = 3, column = 4)),
                )
            f.state.lastSvg = "<svg>keep</svg>"
            f.state.lastError = "keep"
            f.state.script = "buffer"
            val r = f.call("kuml_desktop_validate")
            r["ok"]!!.jsonPrimitive.content shouldBe "false"
            val d = r["diagnostics"]!!.jsonArray[0].jsonObject
            d["message"]!!.jsonPrimitive.content shouldBe "bad"
            d["line"]!!.jsonPrimitive.content shouldBe "3"
            f.call("kuml_desktop_validate", buildJsonObject { put("script", "other") })
            f.validateCalls shouldBe 2
            f.state.script shouldBe "buffer"
            f.state.lastSvg shouldBe "<svg>keep</svg>"
            f.state.lastError shouldBe "keep"
            f.state.isDirty shouldBe false
        }

        test("validate rejects oversized scripts") {
            val f = Fixture()
            f
                .error(
                    "kuml_desktop_validate",
                    buildJsonObject {
                        put("script", "x".repeat(DesktopMcpTools.MAX_SCRIPT_BYTES + 1))
                    },
                )["error"]!!
                .jsonPrimitive.content shouldBe "SCRIPT_TOO_LARGE"
            f.validateCalls shouldBe 0
        }

        test("render success returns ok and the svg") {
            val f = Fixture()
            val r = f.call("kuml_desktop_render")
            r["ok"]!!.jsonPrimitive.content shouldBe "true"
            r["svg"]!!.jsonPrimitive.content shouldBe "<svg/>"
        }

        test("render error returns ok=false with the message") {
            val f = Fixture(renderResult = CompletableDeferred(DesktopRenderResult.Error(message = "kaputt")))
            val r = f.call("kuml_desktop_render")
            r["ok"]!!.jsonPrimitive.content shouldBe "false"
            r["error"]!!.jsonPrimitive.content shouldBe "kaputt"
        }

        test("render with an oversized svg yields SVG_TOO_LARGE") {
            val big = "x".repeat(DesktopMcpTools.MAX_SVG_BYTES + 1)
            val f = Fixture(renderResult = CompletableDeferred(DesktopRenderResult.Svg(svg = big)))
            val e = f.error("kuml_desktop_render")
            e["error"]!!.jsonPrimitive.content shouldBe "SVG_TOO_LARGE"
            e["size"]!!.jsonPrimitive.content shouldBe (DesktopMcpTools.MAX_SVG_BYTES + 1).toString()
        }

        test("render superseded by a newer render yields RENDER_SUPERSEDED") {
            val cancelled = CompletableDeferred<DesktopRenderResult?>().also { it.cancel() }
            Fixture(renderResult = cancelled)
                .error("kuml_desktop_render")["error"]!!
                .jsonPrimitive.content shouldBe "RENDER_SUPERSEDED"
        }

        test("a render that never finishes yields RENDER_TIMEOUT") {
            val f = Fixture(renderResult = CompletableDeferred(), renderTimeoutMs = 200)
            f.error("kuml_desktop_render")["error"]!!.jsonPrimitive.content shouldBe "RENDER_TIMEOUT"
        }

        test("render that failed unexpectedly reports lastError") {
            val f = Fixture(renderResult = CompletableDeferred<DesktopRenderResult?>().also { it.complete(null) })
            f.state.lastError = "Unerwarteter Fehler: x"
            f.call("kuml_desktop_render")["error"]!!.jsonPrimitive.content shouldContain "Unerwarteter Fehler"
        }

        test("revisionOf is deterministic, 16 hex chars, and input sensitive") {
            DesktopMcpTools.revisionOf("a") shouldBe DesktopMcpTools.revisionOf("a")
            DesktopMcpTools.revisionOf("a").matches(Regex("[0-9a-f]{16}")) shouldBe true
            (DesktopMcpTools.revisionOf("a") != DesktopMcpTools.revisionOf("b")) shouldBe true
        }

        test("eval gate: a second evaluation is refused with BUSY while one holds the gate") {
            val f = Fixture()
            val gated =
                DesktopMcpTools(
                    state = f.state,
                    render = McpRenderPort { f.renderResult },
                    validate = McpValidatePort { f.validateResult },
                    editorActions = { null },
                    onNotice = {},
                    uiDispatcher = Dispatchers.Unconfined,
                    evalDispatcher = Dispatchers.Unconfined,
                    gateWaitMs = 100,
                )
            gated.evalGate.acquire()
            try {
                val e =
                    shouldThrow<McpToolException> {
                        gated.tools.first { it.descriptor.name == "kuml_desktop_render" }.call(
                            JsonObject(emptyMap()),
                        )
                    }
                e.message!! shouldContain "BUSY"
                val wrapped = SerializedTool(delegate = gated.tools[0], gate = gated.evalGate, waitMs = 100)
                shouldThrow<McpToolException> { wrapped.call(JsonObject(emptyMap())) }.message!! shouldContain "BUSY"
            } finally {
                gated.evalGate.release()
            }
        }
    })
