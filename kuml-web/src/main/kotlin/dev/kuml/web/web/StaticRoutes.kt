package dev.kuml.web.web

import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.routing

/**
 * Serves static web assets from the classpath resource directory `web/static/`.
 *
 * Routes:
 * - `GET /`           → index.html
 * - `GET /app.css`    → app.css
 * - `GET /assets/app.js` → the Vite bundle generated from `frontend/app.js` (see `kuml-web/DEVELOPMENT.adoc`)
 */
internal fun Application.configureStaticRoutes() {
    routing {
        staticResources("/", "web/static") {
            default("index.html")
        }
    }
}
