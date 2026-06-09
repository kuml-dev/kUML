package dev.kuml.web

import dev.kuml.web.api.configureApiRoutes
import dev.kuml.web.render.EngineRegistration
import dev.kuml.web.web.configureStaticRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json

/**
 * kUML Web Server.
 *
 * Starts an embedded Ktor/Netty HTTP server that provides:
 * - A single-page web application at `GET /` for live SVG preview
 * - A REST API under `/api/` for rendering, themes, examples, and health
 *
 * Call [start] to launch the server. The call blocks until the server stops.
 */
object KumlWebServer {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }

    /**
     * Starts the kUML web server on [host]:[port].
     *
     * This call blocks. Press Ctrl-C to stop the server.
     */
    fun start(
        host: String = "0.0.0.0",
        port: Int = 8080,
    ) {
        println("Starting kUML Web UI on http://$host:$port")
        println("Open http://localhost:$port in your browser")
        embeddedServer(
            factory = Netty,
            host = host,
            port = port,
            module = Application::kumlWebModule,
        ).start(wait = true)
    }
}

/**
 * Ktor application module for kUML Web.
 *
 * Can be used directly in tests via `testApplication { application(Application::kumlWebModule) }`.
 */
fun Application.kumlWebModule() {
    // Register engines and themes once at startup
    EngineRegistration.ensure()

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
        )
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(
                text = "Internal server error: ${cause.message}",
                status = io.ktor.http.HttpStatusCode.InternalServerError,
            )
        }
    }

    configureApiRoutes()
    configureStaticRoutes()
}
