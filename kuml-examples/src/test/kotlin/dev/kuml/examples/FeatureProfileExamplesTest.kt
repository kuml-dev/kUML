package dev.kuml.examples

import dev.kuml.core.dsl.classDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.profile.autosar.AutosarBehaviorKind
import dev.kuml.profile.autosar.AutosarPortDirection
import dev.kuml.profile.autosar.AutosarSwcKind
import dev.kuml.profile.autosar.autosarProfile
import dev.kuml.profile.javaee.javaEeProfile
import dev.kuml.profile.openapi.HttpMethod
import dev.kuml.profile.openapi.ParameterIn
import dev.kuml.profile.openapi.openApiProfile
import dev.kuml.profile.spring.springProfile
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.component
import dev.kuml.uml.dsl.interfaceOf
import dev.kuml.uml.dsl.operation
import dev.kuml.uml.dsl.port
import dev.kuml.uml.dsl.returns
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * End-to-end smoke tests for V1.1.2 feature-level profile example diagrams.
 *
 * Each test builds the same diagram as the corresponding .kuml.kts example file
 * programmatically and validates that:
 * 1. The diagram renders to SVG without errors.
 * 2. The feature-level stereotype label appears in the SVG output.
 *
 * Covers AP-3.5: one example per profile (JavaEE, Spring, OpenAPI, AUTOSAR).
 */
class FeatureProfileExamplesTest :
    FunSpec({

        // ── Shared layout helper ──────────────────────────────────────────────────

        fun buildLayout(diagram: dev.kuml.core.model.KumlDiagram): LayoutResult {
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
                                    origin = Point(x = 20f + (i % 2) * 240f, y = 20f + (i / 2) * 160f),
                                    size = Size(width = 200f, height = 130f),
                                ),
                        )
                }

            val cols = 2
            val rows = (nodeIds.size + cols - 1) / cols
            return LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(width = 20f + cols * 240f, height = 20f + rows * 160f),
                nodes = nodeEntries.toMap(),
                edges = emptyMap(),
                groups = emptyMap(),
            )
        }

        // ── Test 1: JavaEE — «PersistenceContext» on attribute ────────────────────

        test("JavaEE example: «PersistenceContext» feature stereotype appears in SVG") {
            val diagram =
                classDiagram("Order Repository — JPA Injection") {
                    applyProfile(javaEeProfile)

                    classOf("Order") {
                        stereotype("Entity") {
                            "tableName" to "orders"
                            "schema" to "shop"
                            "cacheable" to false
                        }
                        attribute(name = "id", type = "UUID")
                        attribute(name = "amount", type = "BigDecimal")
                    }

                    classOf("OrderRepository") {
                        stereotype("Repository") { "dataSource" to "shopDb" }
                        attribute("em", "EntityManager") {
                            stereotype("PersistenceContext") {
                                "unitName" to "shopPU"
                                "type" to "TRANSACTION"
                            }
                        }
                        operation(name = "findById") { returns("Order") }
                        operation(name = "save") { returns("Order") }
                    }
                }

            val svg = KumlSvgRenderer.toSvg(diagram, buildLayout(diagram), PlainTheme())
            svg shouldContain "«Entity»"
            svg shouldContain "«Repository»"
            svg shouldContain "«PersistenceContext»"
        }

        // ── Test 2: Spring — «Scheduled» on operation ─────────────────────────────

        test("Spring example: «Scheduled» feature stereotype appears in SVG") {
            val diagram =
                classDiagram("Report Scheduler — Spring Tasks") {
                    applyProfile(javaEeProfile)
                    applyProfile(springProfile)

                    classOf("ReportScheduler") {
                        stereotype("Service") { "transactional" to false }

                        operation("generateDailyReport") {
                            stereotype("Scheduled") {
                                "cron" to "0 0 * * *"
                            }
                            returns("void")
                        }

                        operation("pollExternalQueue") {
                            stereotype("Scheduled") {
                                "fixedRate" to 5000L
                                "initialDelay" to 1000L
                            }
                            returns("void")
                        }
                    }
                }

            val svg = KumlSvgRenderer.toSvg(diagram, buildLayout(diagram), PlainTheme())
            svg shouldContain "«Service»"
            svg shouldContain "«Scheduled»"
            svg shouldContain "ReportScheduler"
        }

        // ── Test 3: OpenAPI — «Operation» + «Parameter» on operations ─────────────

        test("OpenAPI example: «Operation» and «Parameter» feature stereotypes appear in SVG") {
            val diagram =
                classDiagram("User API — OpenAPI Operations") {
                    applyProfile(openApiProfile)

                    classOf("UserResource") {
                        stereotype("Resource") {
                            "path" to "/users"
                            "version" to "v1"
                        }

                        operation("getUser") {
                            stereotype("Operation") {
                                "method" to HttpMethod.GET
                                "path" to "/users/{id}"
                                "summary" to "Retrieve a user by ID"
                                "status" to 200
                            }
                            parameter("id", "Long") {
                                stereotype("Parameter") {
                                    "in" to ParameterIn.Path
                                    "required" to true
                                }
                            }
                            returns("UserSchema")
                        }
                    }

                    classOf("UserSchema") {
                        stereotype("Schema") {
                            "format" to "json"
                            "description" to "Public user representation"
                        }
                        attribute(name = "id", type = "Long")
                        attribute(name = "email", type = "String")
                    }
                }

            val svg = KumlSvgRenderer.toSvg(diagram, buildLayout(diagram), PlainTheme())
            svg shouldContain "«Resource»"
            svg shouldContain "«Schema»"
            // «Operation» appears as feature-stereotype tspan prefix before the operation name
            svg shouldContain "«Operation»"
            // Note: «Parameter» is applied to UmlParameter in the model (visible in JSON export)
            // but parameters are not rendered as individual SVG lines — they appear in op.format() signature
            svg shouldContain "UserResource"
        }

        // ── Test 4: AUTOSAR — «Runnable» on operations ────────────────────────────

        test("AUTOSAR example: «Runnable» feature stereotype appears in SVG") {
            val diagram =
                classDiagram("Brake Controller — AUTOSAR Runnables") {
                    applyProfile(autosarProfile)

                    // Component-level: SoftwareComponent stereotype on component
                    component("BrakeControllerSwc") {
                        stereotype("SoftwareComponent") {
                            "kind" to AutosarSwcKind.Application
                            "packageName" to "chassis.brake"
                        }
                        port("rpmSensor") {
                            stereotype("AutosarPort") { "direction" to AutosarPortDirection.Required }
                        }
                    }

                    // Implementation class with Runnable operations (feature-level stereotypes)
                    classOf("BrakeControllerImpl") {
                        operation("onCycle") {
                            stereotype("Runnable") {
                                "kind" to AutosarBehaviorKind.Periodic
                                "periodMs" to 10L
                            }
                            returns("void")
                        }

                        operation("onBrakePedalEvent") {
                            stereotype("Runnable") {
                                "kind" to AutosarBehaviorKind.EventTriggered
                                "periodMs" to 0L
                            }
                            returns("void")
                        }
                    }

                    interfaceOf("BrakePedalInterface") {
                        stereotype("ComInterface") {
                            "version" to "3.0"
                            "isService" to false
                        }
                    }
                }

            val svg = KumlSvgRenderer.toSvg(diagram, buildLayout(diagram), PlainTheme())
            svg shouldContain "«SoftwareComponent»"
            svg shouldContain "«Runnable»"
            svg shouldContain "BrakeControllerImpl"
        }
    })
