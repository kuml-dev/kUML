package dev.kuml.web.api

import dev.kuml.web.kumlWebModule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
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

class ApiRoutesLatexTest :
    FunSpec({

        val umlScript =
            """
            classDiagram(name = "Test") {
                classOf(name = "Alpha")
                classOf(name = "Beta")
            }
            """.trimIndent()

        val sysml2BddScript =
            """
            import dev.kuml.sysml2.dsl.sysml2Model
            sysml2Model("TestModel") {
                val vehicle = partDef("Vehicle")
                bdd("Test BDD") {
                    include(vehicle)
                }
            }
            """.trimIndent()

        test("POST /api/render with format=latex returns 200 and latex field") {
            testApplication {
                application { kumlWebModule() }
                val requestBody = json.encodeToString(RenderRequest(script = umlScript, format = "latex"))
                val response =
                    client.post("/api/render") {
                        contentType(ContentType.Application.Json)
                        setBody(requestBody)
                    }
                response.status shouldBe HttpStatusCode.OK
                val body = json.decodeFromString<RenderResponse>(response.bodyAsText())
                body.ok.shouldBeTrue()
                body.format shouldBe "latex"
                val latex1 = requireNotNull(body.latex) { "latex field should not be null" }
                latex1.shouldNotBeBlank()
                latex1 shouldContain "tikzpicture"
            }
        }

        test("POST /api/render with format=latex and standaloneTex=true returns full document") {
            testApplication {
                application { kumlWebModule() }
                val requestBody =
                    json.encodeToString(RenderRequest(script = umlScript, format = "latex", standaloneTex = true))
                val response =
                    client.post("/api/render") {
                        contentType(ContentType.Application.Json)
                        setBody(requestBody)
                    }
                response.status shouldBe HttpStatusCode.OK
                val body = json.decodeFromString<RenderResponse>(response.bodyAsText())
                body.ok.shouldBeTrue()
                val latex2 = requireNotNull(body.latex) { "latex field should not be null" }
                latex2 shouldContain "\\documentclass"
                latex2 shouldContain "\\begin{document}"
                latex2 shouldContain "\\end{document}"
            }
        }

        test("POST /api/render with invalid script returns 422 and error") {
            testApplication {
                application { kumlWebModule() }
                val badScript = "this is not valid kUML syntax @@@"
                val requestBody = json.encodeToString(RenderRequest(script = badScript, format = "latex"))
                val response =
                    client.post("/api/render") {
                        contentType(ContentType.Application.Json)
                        setBody(requestBody)
                    }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
                val body = json.decodeFromString<RenderResponse>(response.bodyAsText())
                body.ok shouldBe false
                body.error shouldNotBe null
                body.latex shouldBe null
            }
        }

        test("POST /api/render with SysML2 BDD and format=latex returns latex") {
            testApplication {
                application { kumlWebModule() }
                val requestBody = json.encodeToString(RenderRequest(script = sysml2BddScript, format = "latex"))
                val response =
                    client.post("/api/render") {
                        contentType(ContentType.Application.Json)
                        setBody(requestBody)
                    }
                response.status shouldBe HttpStatusCode.OK
                val body = json.decodeFromString<RenderResponse>(response.bodyAsText())
                body.ok.shouldBeTrue()
                val latex4 = requireNotNull(body.latex) { "latex field should not be null" }
                latex4.shouldNotBeBlank()
                latex4 shouldContain "tikzpicture"
                latex4 shouldNotContain "\\documentclass"
            }
        }

        val ermScript =
            """
            ermModel("Blog") {
                val author = entity("Author") { id() }
                val post = entity("Post") { id(); foreignKey(name = "author_id", references = author, nullable = false) }
                relationship(from = author, to = post, name = "writes")
                diagram(name = "D", notation = ErmNotation.CHEN)
            }
            """.trimIndent()

        suspend fun io.ktor.client.HttpClient.render(request: RenderRequest) =
            post("/api/render") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(request))
            }

        test("Nacharbeit 128: an SVG response says LaTeX is available for UML") {
            testApplication {
                application { kumlWebModule() }
                val body = json.decodeFromString<RenderResponse>(client.render(RenderRequest(script = umlScript)).bodyAsText())
                body.ok.shouldBeTrue()
                body.latexSupported shouldBe true
                body.latexUnsupportedReason shouldBe null
            }
        }

        test("Nacharbeit 128: an SVG response of a Chen diagram carries the reason, and the LaTeX request gives 422 with it") {
            testApplication {
                application { kumlWebModule() }
                val svg = json.decodeFromString<RenderResponse>(client.render(RenderRequest(script = ermScript)).bodyAsText())
                svg.ok.shouldBeTrue()
                svg.latexSupported shouldBe false
                val reason = requireNotNull(svg.latexUnsupportedReason)
                reason shouldContain "CHEN"

                val response = client.render(RenderRequest(script = ermScript, format = "latex"))
                response.status shouldBe HttpStatusCode.UnprocessableEntity
                json.decodeFromString<RenderResponse>(response.bodyAsText()).error shouldBe reason
            }
        }

        test("Nacharbeit 128: PNG, LaTeX and error responses leave the LaTeX fields empty") {
            testApplication {
                application { kumlWebModule() }
                val png =
                    json.decodeFromString<RenderResponse>(
                        client.render(RenderRequest(script = umlScript, format = "png")).bodyAsText(),
                    )
                png.latexSupported shouldBe null
                val tex =
                    json.decodeFromString<RenderResponse>(
                        client.render(RenderRequest(script = umlScript, format = "latex")).bodyAsText(),
                    )
                tex.latexSupported shouldBe null
                val bad = json.decodeFromString<RenderResponse>(client.render(RenderRequest(script = "@@@")).bodyAsText())
                bad.latexSupported shouldBe null
                bad.latexUnsupportedReason shouldBe null
            }
        }

        test("Nacharbeit 128: the LaTeX source travels as a JSON field, not as a file download") {
            testApplication {
                application { kumlWebModule() }
                val response = client.render(RenderRequest(script = umlScript, format = "latex"))
                response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                response.headers["Content-Disposition"] shouldBe null
            }
        }
    })
