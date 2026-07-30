package dev.kuml.profile.javaee

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
import dev.kuml.renderer.theme.core.StereotypeTheme
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.operation
import dev.kuml.uml.dsl.returns
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * AP-5a.1 render tests for the JavaEE profile.
 *
 * Builds the User Domain diagram programmatically and validates SVG output
 * contains correct stereotype headers. Also tests Tagged-Value compartment
 * end-to-end with showTaggedValues = true.
 */
class JavaEeRenderTest :
    FunSpec({

        val diagram =
            classDiagram(name = "User Domain") {
                applyProfile(javaEeProfile)

                classOf(name = "User") {
                    stereotype(name = "Entity") {
                        "tableName" to "users"
                        "schema" to "auth"
                        "cacheable" to true
                    }
                    attribute(name = "id", type = "UUID")
                    attribute(name = "email", type = "String")
                    attribute(name = "name", type = "String")
                }

                classOf(name = "UserRepository") {
                    stereotype(name = "Repository") { "dataSource" to "userDb" }
                    operation(name = "findById") { returns("User") }
                    operation(name = "save") { returns("User") }
                }

                classOf(name = "UserService") {
                    stereotype(name = "Service") { "transactional" to true }
                    operation(name = "register") { returns("User") }
                }

                classOf(name = "UserController") {
                    stereotype(name = "Controller") { "requestMapping" to "/api/users" }
                    operation(name = "list") { returns("List<User>") }
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
                                    origin = Point(x = 20f + (i % 2) * 220f, y = 20f + (i / 2) * 140f),
                                    size = Size(width = 180f, height = 100f),
                                ),
                        )
                }
            val nodes = nodeEntries.toMap()

            return LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(width = 480f, height = 340f),
                nodes = nodes,
                edges = emptyMap(),
                groups = emptyMap(),
            )
        }

        // ── Test 1: SVG contains «Entity» stereotype header ───────────────────────

        test("SVG contains Entity stereotype label") {
            val svg = KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = buildLayout(), theme = PlainTheme())
            svg shouldContain "«Entity»"
        }

        // ── Test 2: SVG contains «Repository» stereotype header ───────────────────

        test("SVG contains Repository stereotype label") {
            val svg = KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = buildLayout(), theme = PlainTheme())
            svg shouldContain "«Repository»"
        }

        // ── Test 3: SVG contains «Service» stereotype header ──────────────────────

        test("SVG contains Service stereotype label") {
            val svg = KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = buildLayout(), theme = PlainTheme())
            svg shouldContain "«Service»"
        }

        // ── Test 4: SVG contains «Controller» stereotype header ───────────────────

        test("SVG contains Controller stereotype label") {
            val svg = KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = buildLayout(), theme = PlainTheme())
            svg shouldContain "«Controller»"
        }

        // ── Test 5: Tagged-Value compartment renders when showTaggedValues = true ──

        test("Tagged-Value compartment renders tableName and cacheable when showTaggedValues=true") {
            val themeWithTV =
                PlainTheme().copy(
                    stereotypes = StereotypeTheme(showTaggedValues = true),
                )
            val svg = KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = buildLayout(), theme = themeWithTV)
            svg shouldContain "{tableName = users}"
            svg shouldContain "{cacheable = true}"
            svg shouldContain "kuml-tagged-value"
        }
    })
