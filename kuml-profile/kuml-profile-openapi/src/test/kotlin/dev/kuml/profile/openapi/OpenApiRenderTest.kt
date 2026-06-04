package dev.kuml.profile.openapi

import dev.kuml.core.dsl.classDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * AP-5b.1 render tests for the OpenAPI profile.
 *
 * Builds the User API diagram programmatically and validates SVG output
 * contains correct stereotype headers for Resource and Schema.
 */
class OpenApiRenderTest :
    FunSpec({

        // User API diagram — Resource + 2x Schema
        val diagram =
            classDiagram("User API") {
                applyProfile(openApiProfile)

                classOf("UserResource") {
                    stereotype("Resource") {
                        "path" to "/users"
                        "version" to "v2"
                    }
                }

                classOf("UserSchema") {
                    stereotype("Schema") {
                        "format" to "json"
                        "description" to "Public user representation"
                    }
                    attribute(name = "id", type = "UUID")
                    attribute(name = "email", type = "String")
                }

                classOf("ErrorSchema") {
                    stereotype("Schema") {
                        "format" to "json"
                        "description" to "Standard RFC-7807 error body"
                    }
                    attribute(name = "type", type = "String")
                    attribute(name = "title", type = "String")
                    attribute(name = "status", type = "Int")
                }
            }

        fun buildLayout(): LayoutResult {
            val nodeIds =
                diagram.elements
                    .filterIsInstance<UmlNamedElement>()
                    .map { NodeId(it.id) }

            val nodeEntries =
                nodeIds.mapIndexed { i, nodeId ->
                    nodeId to
                        NodeLayout(
                            bounds =
                                Rect(
                                    origin = Point(x = 20f + i * 220f, y = 20f),
                                    size = Size(width = 180f, height = 100f),
                                ),
                        )
                }
            val nodes = nodeEntries.toMap()

            return LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(width = 700f, height = 160f),
                nodes = nodes,
                edges = emptyMap(),
                groups = emptyMap(),
            )
        }

        // ── Test 1: «Resource» over UserResource ──────────────────────────────────

        test("SVG contains Resource stereotype label over UserResource") {
            val svg = KumlSvgRenderer.toSvg(diagram, buildLayout(), PlainTheme())
            svg shouldContain "«Resource»"
            svg shouldContain "UserResource"
        }

        // ── Test 2: «Schema» over UserSchema ──────────────────────────────────────

        test("SVG contains Schema stereotype label over UserSchema") {
            val svg = KumlSvgRenderer.toSvg(diagram, buildLayout(), PlainTheme())
            svg shouldContain "«Schema»"
            svg shouldContain "UserSchema"
        }

        // ── Test 3: «Schema» appears at least twice (UserSchema + ErrorSchema) ────

        test("SVG contains Schema stereotype label at least twice") {
            val svg = KumlSvgRenderer.toSvg(diagram, buildLayout(), PlainTheme())
            var count = 0
            var idx = svg.indexOf("«Schema»")
            while (idx >= 0) {
                count++
                idx = svg.indexOf("«Schema»", idx + 1)
            }
            assert(count >= 2) { "Expected at least 2 «Schema» labels, found $count" }
        }

        // ── Test 4: ErrorSchema class name appears ────────────────────────────────

        test("SVG contains ErrorSchema class name") {
            val svg = KumlSvgRenderer.toSvg(diagram, buildLayout(), PlainTheme())
            svg shouldContain "ErrorSchema"
        }

        // ── Test 5: Diagram has 3 elements ────────────────────────────────────────

        test("User API diagram has 3 elements (1 Resource + 2 Schema classes)") {
            diagram.elements.size shouldBe 3
        }
    })
