package dev.kuml.cli.update

import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * End-to-end test of [HttpReleasesClient] against an embedded JDK
 * `com.sun.net.httpserver` instance. Proves that:
 *
 *  - We actually issue the request with the expected headers (User-Agent,
 *    Accept, X-GitHub-Api-Version) — GitHub will 403 us without them.
 *  - 2xx responses become `Result.Ok`.
 *  - 4xx/5xx responses become `Result.HttpError` rather than throwing.
 *  - The list endpoint deserialises into a list of [ReleaseInfo].
 *
 * No external network is touched.
 */
class HttpReleasesClientTest :
    StringSpec({

        "200 OK from /releases/latest deserialises to a ReleaseInfo" {
            val seenHeaders = mutableMapOf<String, String>()
            withServer(
                { exchange ->
                    seenHeaders["User-Agent"] = exchange.requestHeaders.getFirst("User-Agent").orEmpty()
                    seenHeaders["Accept"] = exchange.requestHeaders.getFirst("Accept").orEmpty()
                    seenHeaders["X-GitHub-Api-Version"] =
                        exchange.requestHeaders.getFirst("X-GitHub-Api-Version").orEmpty()
                    respond(
                        exchange = exchange,
                        status = 200,
                        body = """{"tag_name":"v0.4.0","draft":false,"prerelease":false,"body":"notes"}""",
                    )
                },
            ) { baseUrl ->
                val client = HttpReleasesClient(baseUrl = baseUrl, repo = "kuml-dev/kUML", userAgent = "kuml-cli-test")
                val result = client.fetchLatest()
                result.shouldBeInstanceOf<ReleasesClient.Result.Ok>()
                result.release.tagName shouldBe "v0.4.0"

                seenHeaders["User-Agent"] shouldBe "kuml-cli-test"
                // GitHub's own conventions — we're a polite client.
                seenHeaders["Accept"].shouldContain("github+json")
                seenHeaders["X-GitHub-Api-Version"] shouldBe "2022-11-28"
            }
        }

        "404 from /releases/latest becomes Result.HttpError (no exception)" {
            withServer(
                { exchange ->
                    respond(exchange = exchange, status = 404, body = """{"message":"Not Found"}""")
                },
            ) { baseUrl ->
                val client = HttpReleasesClient(baseUrl = baseUrl)
                val result = client.fetchLatest()
                result.shouldBeInstanceOf<ReleasesClient.Result.HttpError>()
                result.statusCode shouldBe 404
            }
        }

        "fetchAll deserialises a list response" {
            withServer(
                { exchange ->
                    respond(
                        exchange = exchange,
                        status = 200,
                        body =
                            """
                            [
                              {"tag_name":"v0.5.0-rc.1","draft":false,"prerelease":true,"body":""},
                              {"tag_name":"v0.4.0","draft":false,"prerelease":false,"body":""}
                            ]
                            """.trimIndent(),
                    )
                },
            ) { baseUrl ->
                val client = HttpReleasesClient(baseUrl = baseUrl)
                val result = client.fetchAll(limit = 5)
                result.shouldBeInstanceOf<ReleasesClient.ListResult.Ok>()
                result.releases.size shouldBe 2
                result.releases.first().isPreRelease shouldBe true
            }
        }
    })

// ─────────────────────────────────────────────────────────────────────────────
// Embedded-server helpers. Bind on port 0 so we never collide with anything
// the developer has running locally.

private fun withServer(
    handler: HttpHandler,
    block: (baseUrl: String) -> Unit,
) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", handler)
    server.executor = null // use a default executor — single-threaded is fine for tests
    server.start()
    try {
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        block(baseUrl)
    } finally {
        server.stop(0)
    }
}

private fun respond(
    exchange: com.sun.net.httpserver.HttpExchange,
    status: Int,
    body: String,
) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}
