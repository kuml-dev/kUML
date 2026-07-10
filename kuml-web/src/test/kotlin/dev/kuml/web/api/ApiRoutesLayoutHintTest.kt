package dev.kuml.web.api

import dev.kuml.web.kumlWebModule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

class ApiRoutesLayoutHintTest :
    FunSpec({

        val umlScript =
            """
            classDiagram(name = "Test") {
                classOf(name = "Alpha")
                classOf(name = "Beta")
            }
            """.trimIndent()

        test("POST /api/layout/hint with a valid UML drop returns ok=true with an updated script") {
            testApplication {
                application { kumlWebModule() }
                val requestBody =
                    json.encodeToString(LayoutHintRequest(script = umlScript, elementId = "Alpha", col = 1, row = 0))
                val response =
                    client.post("/api/layout/hint") {
                        contentType(ContentType.Application.Json)
                        setBody(requestBody)
                    }
                response.status shouldBe HttpStatusCode.OK
                val body = json.decodeFromString<LayoutHintResponse>(response.bodyAsText())
                body.ok.shouldBeTrue()
                body.script shouldContain "layout {"
                body.script shouldContain "col = 1"
                body.script shouldContain "row = 0"
            }
        }

        test("POST /api/layout/hint with a non-UML script returns 422 with ok=false") {
            testApplication {
                application { kumlWebModule() }
                val c4Script =
                    """
                    c4Model(name = "TestSystem") {
                        val user = person(name = "User")
                    }
                    """.trimIndent()
                val requestBody =
                    json.encodeToString(LayoutHintRequest(script = c4Script, elementId = "User", col = 0, row = 0))
                val response =
                    client.post("/api/layout/hint") {
                        contentType(ContentType.Application.Json)
                        setBody(requestBody)
                    }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
                val body = json.decodeFromString<LayoutHintResponse>(response.bodyAsText())
                body.ok shouldBe false
                body.error shouldContain "UML"
            }
        }

        test("POST /api/layout/hint with an unknown element id returns 422 with ok=false") {
            testApplication {
                application { kumlWebModule() }
                val requestBody =
                    json.encodeToString(LayoutHintRequest(script = umlScript, elementId = "Nope", col = 0, row = 0))
                val response =
                    client.post("/api/layout/hint") {
                        contentType(ContentType.Application.Json)
                        setBody(requestBody)
                    }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
                val body = json.decodeFromString<LayoutHintResponse>(response.bodyAsText())
                body.ok shouldBe false
                body.error shouldContain "Nope"
            }
        }

        test("POST /api/layout/hint targeting a relationship id returns 422 with ok=false") {
            testApplication {
                application { kumlWebModule() }
                val scriptWithAssociation =
                    """
                    classDiagram(name = "Test") {
                        val a = classOf(name = "Alpha")
                        val b = classOf(name = "Beta")
                        association(source = a, target = b)
                    }
                    """.trimIndent()
                val requestBody =
                    json.encodeToString(
                        LayoutHintRequest(script = scriptWithAssociation, elementId = "assoc::Alpha-->Beta", col = 0, row = 0),
                    )
                val response =
                    client.post("/api/layout/hint") {
                        contentType(ContentType.Application.Json)
                        setBody(requestBody)
                    }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
                val body = json.decodeFromString<LayoutHintResponse>(response.bodyAsText())
                body.ok shouldBe false
                body.error shouldContain "relationship/edge"
            }
        }
    })
