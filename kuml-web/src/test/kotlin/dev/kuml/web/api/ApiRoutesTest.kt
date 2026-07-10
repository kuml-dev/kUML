package dev.kuml.web.api

import dev.kuml.web.kumlWebModule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

class ApiRoutesTest :
    FunSpec({

        val umlScript =
            """
            classDiagram(name = "Test") {
                classOf(name = "Alpha")
                classOf(name = "Beta")
            }
            """.trimIndent()

        test("GET /api/health returns 200 with status ok") {
            testApplication {
                application { kumlWebModule() }
                val response = client.get("/api/health")
                response.status shouldBe HttpStatusCode.OK
                val body = json.decodeFromString<HealthResponse>(response.bodyAsText())
                body.status shouldBe "ok"
            }
        }

        test("GET /api/themes returns 200 with non-empty themes list") {
            testApplication {
                application { kumlWebModule() }
                val response = client.get("/api/themes")
                response.status shouldBe HttpStatusCode.OK
                val body = json.decodeFromString<ThemesResponse>(response.bodyAsText())
                body.themes.shouldNotBeEmpty()
            }
        }

        test("GET /api/examples returns 200 with 4 entries") {
            testApplication {
                application { kumlWebModule() }
                val response = client.get("/api/examples")
                response.status shouldBe HttpStatusCode.OK
                val body = json.decodeFromString<ExamplesResponse>(response.bodyAsText())
                body.examples.size shouldBe 4
            }
        }

        test("GET /api/examples/uml-class returns 200 with Kotlin script content") {
            testApplication {
                application { kumlWebModule() }
                val response = client.get("/api/examples/uml-class")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "classDiagram"
            }
        }

        test("GET /api/examples/erm-martin returns 200 with ermModel script content") {
            testApplication {
                application { kumlWebModule() }
                val response = client.get("/api/examples/erm-martin")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "ermModel"
            }
        }

        test("POST /api/render with valid UML script returns ok=true with SVG") {
            testApplication {
                application { kumlWebModule() }
                val requestBody = json.encodeToString(RenderRequest(script = umlScript, format = "svg"))
                val response =
                    client.post("/api/render") {
                        contentType(ContentType.Application.Json)
                        setBody(requestBody)
                    }
                response.status shouldBe HttpStatusCode.OK
                val body = json.decodeFromString<RenderResponse>(response.bodyAsText())
                body.ok.shouldBeTrue()
                body.svg shouldNotBe null
                body.svg!! shouldContain "<svg"
            }
        }

        test("POST /api/render with valid UML script returns nodes and grid in the response") {
            testApplication {
                application { kumlWebModule() }
                val requestBody = json.encodeToString(RenderRequest(script = umlScript, format = "svg"))
                val response =
                    client.post("/api/render") {
                        contentType(ContentType.Application.Json)
                        setBody(requestBody)
                    }
                response.status shouldBe HttpStatusCode.OK
                val body = json.decodeFromString<RenderResponse>(response.bodyAsText())
                body.ok.shouldBeTrue()
                body.nodes.size shouldBe 2
                body.grid shouldNotBe null
                (body.grid!!.cols >= 1) shouldBe true
            }
        }

        test("POST /api/render with format png returns ok=true with pngBase64") {
            testApplication {
                application { kumlWebModule() }
                val requestBody = json.encodeToString(RenderRequest(script = umlScript, format = "png"))
                val response =
                    client.post("/api/render") {
                        contentType(ContentType.Application.Json)
                        setBody(requestBody)
                    }
                response.status shouldBe HttpStatusCode.OK
                val body = json.decodeFromString<RenderResponse>(response.bodyAsText())
                body.ok.shouldBeTrue()
                body.pngBase64 shouldNotBe null
                body.pngBase64!!.shouldNotBeBlank()
            }
        }

        test("POST /api/render with valid ERM script returns ok=true with SVG") {
            testApplication {
                application { kumlWebModule() }
                val ermScript =
                    """
                    ermModel("Blog") {
                        val author = entity("Author") { id() }
                        val post = entity("Post") {
                            id()
                            foreignKey(name = "author_id", references = author, nullable = false)
                        }
                        relationship(from = author, to = post)
                    }
                    """.trimIndent()
                val requestBody = json.encodeToString(RenderRequest(script = ermScript, format = "svg"))
                val response =
                    client.post("/api/render") {
                        contentType(ContentType.Application.Json)
                        setBody(requestBody)
                    }
                response.status shouldBe HttpStatusCode.OK
                val body = json.decodeFromString<RenderResponse>(response.bodyAsText())
                body.ok.shouldBeTrue()
                body.svg shouldNotBe null
                body.svg!! shouldContain "<svg"
            }
        }

        test("POST /api/render with syntax error script returns ok=false with error message") {
            testApplication {
                application { kumlWebModule() }
                val badScript = "this is not valid kUML syntax @@@"
                val requestBody = json.encodeToString(RenderRequest(script = badScript, format = "svg"))
                val response =
                    client.post("/api/render") {
                        contentType(ContentType.Application.Json)
                        setBody(requestBody)
                    }
                val body = json.decodeFromString<RenderResponse>(response.bodyAsText())
                body.ok shouldBe false
                body.error shouldNotBe null
            }
        }
    })
