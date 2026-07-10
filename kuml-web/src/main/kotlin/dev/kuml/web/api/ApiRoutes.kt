package dev.kuml.web.api

import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptGuard
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.layout.bridge.LayoutHintWriter
import dev.kuml.uml.dsl.print.UmlModelDslPrinter
import dev.kuml.web.layout.LayoutHintService
import dev.kuml.web.render.WebRenderPipeline
import dev.kuml.web.render.WebRenderResult
import dev.kuml.web.render.toBase64
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * REST API routes for the kUML Web UI.
 *
 * All routes live under `/api`:
 * - `POST /api/render`          — render a kUML script to SVG or PNG
 * - `POST /api/layout/hint`     — apply a drag-and-drop grid placement, return updated script
 * - `GET  /api/themes`          — list available themes
 * - `GET  /api/examples`        — list bundled example scripts
 * - `GET  /api/examples/{name}` — fetch a specific example script by name
 * - `GET  /api/health`          — server health check
 */
internal fun Application.configureApiRoutes(version: String = "0.6.0") {
    val layoutHintService =
        LayoutHintService(
            parse = ::parseUmlDiagram,
            print = UmlModelDslPrinter::print,
        )
    routing {
        route("/api") {
            // ── POST /api/render ────────────────────────────────────────────
            post("/render") {
                val req = call.receive<RenderRequest>()
                val result =
                    WebRenderPipeline.render(
                        script = req.script,
                        format = req.format,
                        themeName = req.theme,
                        layoutOverride = req.layout,
                        widthPx = req.widthPx,
                        standaloneTex = req.standaloneTex,
                        notation = req.notation,
                    )
                val response =
                    when (result) {
                        is WebRenderResult.Svg ->
                            RenderResponse(
                                ok = true,
                                format = "svg",
                                svg = result.svg,
                                durationMs = result.durationMs,
                            )
                        is WebRenderResult.Png ->
                            RenderResponse(
                                ok = true,
                                format = "png",
                                pngBase64 = result.pngBytes.toBase64(),
                                durationMs = result.durationMs,
                            )
                        is WebRenderResult.Latex ->
                            RenderResponse(
                                ok = true,
                                format = "latex",
                                latex = result.tex,
                                durationMs = result.durationMs,
                            )
                        is WebRenderResult.Error ->
                            RenderResponse(
                                ok = false,
                                error = result.message,
                            )
                    }
                val status = if (response.ok) HttpStatusCode.OK else HttpStatusCode.UnprocessableEntity
                call.respond(status, response)
            }

            // ── POST /api/layout/hint ───────────────────────────────────────
            post("/layout/hint") {
                val req = call.receive<LayoutHintRequest>()
                val result =
                    layoutHintService.applyDrop(
                        script = req.script,
                        elementId = req.elementId,
                        cell = LayoutHintWriter.GridCell(col = req.col, row = req.row),
                    )
                val response =
                    result.fold(
                        onSuccess = { LayoutHintResponse(ok = true, script = it) },
                        onFailure = { LayoutHintResponse(ok = false, error = it.message ?: "layout hint failed") },
                    )
                val status = if (response.ok) HttpStatusCode.OK else HttpStatusCode.UnprocessableEntity
                call.respond(status, response)
            }

            // ── GET /api/themes ─────────────────────────────────────────────
            get("/themes") {
                val themes =
                    dev.kuml.renderer.theme.core.ThemeRegistry
                        .names()
                        .sorted()
                call.respond(ThemesResponse(themes = themes))
            }

            // ── GET /api/examples ───────────────────────────────────────────
            get("/examples") {
                call.respond(ExamplesResponse(examples = Examples.list()))
            }

            // ── GET /api/examples/{name} ────────────────────────────────────
            get("/examples/{name}") {
                val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing name")
                val source = Examples.source(name)
                if (source == null) {
                    call.respond(HttpStatusCode.NotFound, "Example '$name' not found")
                } else {
                    call.respond(HttpStatusCode.OK, source)
                }
            }

            // ── GET /api/health ─────────────────────────────────────────────
            get("/health") {
                call.respond(HealthResponse(status = "ok", version = version))
            }
        }
    }
}

/**
 * Evaluates [script] and extracts a UML class-diagram [KumlDiagram].
 *
 * Mirrors the front half of [WebRenderPipeline.render] (guard → eval →
 * extract) but throws [IllegalArgumentException] instead of returning a
 * result type — [dev.kuml.web.layout.LayoutHintService.applyDrop] wraps this
 * call in `runCatching`, so any thrown exception (including
 * [dev.kuml.core.script.ScriptSecurityException] from the guard) becomes a
 * `Result.failure` with a caller-facing message.
 *
 * No [dev.kuml.web.render.EngineRegistration.ensure] call is needed here —
 * this path is a pure model transform (parse → rewrite metadata → print),
 * it never touches a layout engine or renderer.
 */
internal fun parseUmlDiagram(script: String): KumlDiagram {
    KumlScriptGuard.validate(script)
    val evalResult = KumlScriptHost.eval(script)
    val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
    if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
        val msg = errors.joinToString("\n") { it.message }
        throw IllegalArgumentException(msg.ifBlank { "Script evaluation failed" })
    }
    val successResult =
        evalResult as? ResultWithDiagnostics.Success
            ?: throw IllegalArgumentException("Script evaluation did not produce a result")

    return when (val extracted = DiagramExtractor.extractAny(successResult.value.returnValue, File("inline.kuml.kts"))) {
        is ExtractedDiagram.Uml -> extracted.diagram
        else -> throw IllegalArgumentException("Only UML class diagrams support grid hints (got ${extracted::class.simpleName})")
    }
}
