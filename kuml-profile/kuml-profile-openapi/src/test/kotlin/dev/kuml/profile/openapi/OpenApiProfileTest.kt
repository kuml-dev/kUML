package dev.kuml.profile.openapi

import dev.kuml.profile.ProfileRegistry
import dev.kuml.profile.UmlMetaclass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * AP-5b.1 profile tests for the OpenAPI profile.
 *
 * Covers:
 * 1. Whitelist — exactly 2 stereotypes (Resource, Schema)
 * 2. Resource has path (required) and version (default v1)
 * 3. Schema has format (default json) and description (default empty)
 * 4. ServiceLoader discovery via ProfileRegistry.loadFromClasspath()
 * 5. Profile metadata
 */
class OpenApiProfileTest :
    FunSpec({

        beforeEach { ProfileRegistry.clear() }
        afterEach { ProfileRegistry.clear() }

        // ── Test 1: Whitelist — exactly 2 stereotypes ────────────────────────────

        test("openApiProfile has exactly 2 stereotypes (Resource, Schema)") {
            val names = openApiProfile.stereotypes.map { it.name }.toSet()
            names shouldContainExactlyInAnyOrder setOf("Resource", "Schema")
            openApiProfile.stereotypes.size shouldBe 2
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
        }

        // ── Test 5: Both stereotypes target UmlMetaclass.Class ───────────────────

        test("all OpenAPI stereotypes target UmlMetaclass.Class") {
            for (s in openApiProfile.stereotypes) {
                s.targetMetaclass shouldBe UmlMetaclass.Class
            }
        }
    })
