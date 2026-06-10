package dev.kuml.web.api

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

/**
 * REST API routes for the kUML Web UI.
 *
 * All routes live under `/api`:
 * - `POST /api/render`          — render a kUML script to SVG or PNG
 * - `GET  /api/themes`          — list available themes
 * - `GET  /api/examples`        — list bundled example scripts
 * - `GET  /api/examples/{name}` — fetch a specific example script by name
 * - `GET  /api/health`          — server health check
 */
internal fun Application.configureApiRoutes(version: String = "0.6.0") {
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
