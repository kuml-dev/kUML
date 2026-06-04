package dev.kuml.profile.openapi

import dev.kuml.core.dsl.classDiagram
import dev.kuml.profile.ProfileRegistry
import dev.kuml.profile.UmlMetaclass
import dev.kuml.uml.UmlClass
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.operation
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * AP-5b.1 profile tests for the OpenAPI profile (V1.1.2 updated).
 *
 * Covers:
 * 1. Whitelist — exactly 4 stereotypes (Resource, Schema, Operation, Parameter)
 * 2. Resource has path (required) and version (default v1)
 * 3. Schema has format (default json) and description (default empty)
 * 4. ServiceLoader discovery via ProfileRegistry.loadFromClasspath()
 * 5. Profile metadata
 * 6. V1.1.2: Operation stereotype tests
 * 7. V1.1.2: Parameter stereotype tests
 */
class OpenApiProfileTest :
    FunSpec({

        beforeEach { ProfileRegistry.clear() }
        afterEach { ProfileRegistry.clear() }

        // ── Test 1: Whitelist — exactly 4 stereotypes (V1.1.2: +Operation, +Parameter)

        test("openApiProfile has exactly 4 stereotypes (Resource, Schema, Operation, Parameter)") {
            val names = openApiProfile.stereotypes.map { it.name }.toSet()
            names shouldContainExactlyInAnyOrder setOf("Resource", "Schema", "Operation", "Parameter")
            openApiProfile.stereotypes.size shouldBe 4
        }

        // ── Test 2: Resource has path (required) and version (default v1) ────────

        test("Resource has path (required) and version (default v1)") {
            val resource = openApiProfile.stereotype("Resource")
            resource shouldNotBe null
            resource!!.properties.size shouldBe 2

            val path = resource.properties.first { it.name == "path" }
            path.required shouldBe true
            path.default shouldBe null

            val version = resource.properties.first { it.name == "version" }
            version.required shouldBe false
            version.default shouldBe "v1"
        }

        // ── Test 3: Schema has format (default json) and description (default empty)

        test("Schema has format (default json) and description (default empty)") {
            val schema = openApiProfile.stereotype("Schema")
            schema shouldNotBe null
            schema!!.properties.size shouldBe 2

            val format = schema.properties.first { it.name == "format" }
            format.required shouldBe false
            format.default shouldBe "json"

            val description = schema.properties.first { it.name == "description" }
            description.required shouldBe false
            description.default shouldBe ""
        }

        // ── Test 4: ServiceLoader discovery ──────────────────────────────────────

        test("openApiProfile is discovered via ProfileRegistry.loadFromClasspath") {
            ProfileRegistry.loadFromClasspath()
            val found = ProfileRegistry.get("dev.kuml.profiles.openapi")
            found shouldNotBe null
            found!!.name shouldBe "OpenAPI"
            found.stereotype("Resource") shouldNotBe null
            found.stereotype("Schema") shouldNotBe null
            found.stereotype("Operation") shouldNotBe null
            found.stereotype("Parameter") shouldNotBe null
        }

        // ── Test 5: Class-level stereotypes target UmlMetaclass.Class ────────────

        test("Resource and Schema target UmlMetaclass.Class") {
            for (name in listOf("Resource", "Schema")) {
                val s = openApiProfile.stereotype(name)
                s shouldNotBe null
                s!!.targetMetaclass shouldBe UmlMetaclass.Class
            }
        }

        // ── Test 6 (V1.1.2): Operation targets UmlMetaclass.Operation ────────────

        test("Operation stereotype targets UmlMetaclass.Operation") {
            val op = openApiProfile.stereotype("Operation")
            op shouldNotBe null
            op!!.targetMetaclass shouldBe UmlMetaclass.Operation
        }

        // ── Test 7 (V1.1.2): Operation has method, path, summary, status ──────────

        test("Operation stereotype has method (GET), path (/), summary (''), status (200)") {
            val op = openApiProfile.stereotype("Operation")
            op shouldNotBe null
            op!!.properties.size shouldBe 4

            val method = op.properties.first { it.name == "method" }
            method.required shouldBe false
            method.default shouldBe HttpMethod.GET

            val path = op.properties.first { it.name == "path" }
            path.required shouldBe false
            path.default shouldBe "/"

            val summary = op.properties.first { it.name == "summary" }
            summary.required shouldBe false
            summary.default shouldBe ""

            val status = op.properties.first { it.name == "status" }
            status.required shouldBe false
            status.default shouldBe 200
        }

        // ── Test 8 (V1.1.2): Parameter targets UmlMetaclass.Parameter ────────────

        test("Parameter stereotype targets UmlMetaclass.Parameter") {
            val param = openApiProfile.stereotype("Parameter")
            param shouldNotBe null
            param!!.targetMetaclass shouldBe UmlMetaclass.Parameter
        }

        // ── Test 9 (V1.1.2): Parameter has 'in' (Query) and required (false) ──────

        test("Parameter stereotype has 'in' (default Query) and required (default false)") {
            val param = openApiProfile.stereotype("Parameter")
            param shouldNotBe null
            param!!.properties.size shouldBe 2

            val inProp = param.properties.first { it.name == "in" }
            inProp.required shouldBe false
            inProp.default shouldBe ParameterIn.Query

            val required = param.properties.first { it.name == "required" }
            required.required shouldBe false
            required.default shouldBe false
        }

        // ── Test 10 (V1.1.2): Operation + Parameter DSL stores appliedStereotypes ──

        test("Operation and Parameter applied via DSL store appliedStereotypes correctly") {
            val diagram =
                classDiagram("OpenAPI DSL Test") {
                    applyProfile(openApiProfile)
                    classOf("UserResource") {
                        stereotype("Resource") { "path" to "/users" }
                        operation("getUser") {
                            stereotype("Operation") {
                                "method" to HttpMethod.GET
                                "path" to "/users/{id}"
                                "summary" to "Get user by ID"
                            }
                            parameter("id", "Long") {
                                stereotype("Parameter") {
                                    "in" to ParameterIn.Path
                                    "required" to true
                                }
                            }
                        }
                    }
                }
            val cls = diagram.elements.filterIsInstance<UmlClass>().first()
            val op = cls.operations.first()
            op.appliedStereotypes.size shouldBe 1
            op.appliedStereotypes.first().stereotypeName shouldBe "Operation"

            val param = op.parameters.first()
            param.appliedStereotypes.size shouldBe 1
            param.appliedStereotypes.first().stereotypeName shouldBe "Parameter"
        }
    })
