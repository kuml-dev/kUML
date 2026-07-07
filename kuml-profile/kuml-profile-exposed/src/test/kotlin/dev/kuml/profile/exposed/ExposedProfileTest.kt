package dev.kuml.profile.exposed

import dev.kuml.core.dsl.classDiagram
import dev.kuml.profile.ProfileRegistry
import dev.kuml.profile.UmlMetaclass
import dev.kuml.uml.UmlClass
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * ADR-0016 Variante A — profile tests for the Exposed profile.
 *
 * Covers:
 * 1. Whitelist — exactly 3 stereotypes
 * 2. Target metaclass checks (Table→Class, Column→Property, FK→Association)
 * 3. Properties with correct required/default definitions
 * 4. ServiceLoader discovery via ProfileRegistry.loadFromClasspath()
 * 5. Profile metadata, including extends(javaEeProfile)
 * 6. DSL-application smoke test
 */
class ExposedProfileTest :
    FunSpec({

        beforeEach { ProfileRegistry.clear() }
        afterEach { ProfileRegistry.clear() }

        // ── Test 1: Whitelist — exactly 3 stereotypes ──────────────────────────────

        test("exposedProfile has exactly 3 stereotypes") {
            val names = exposedProfile.stereotypes.map { it.name }.toSet()
            names shouldContainExactlyInAnyOrder setOf("Table", "Column", "FK")
            exposedProfile.stereotypes.size shouldBe 3
        }

        // ── Test 2: Target metaclasses ──────────────────────────────────────────────

        test("Table targets UmlMetaclass.Class") {
            val s = exposedProfile.stereotype("Table")
            s shouldNotBe null
            s!!.targetMetaclass shouldBe UmlMetaclass.Class
        }

        test("Column targets UmlMetaclass.Property") {
            val s = exposedProfile.stereotype("Column")
            s shouldNotBe null
            s!!.targetMetaclass shouldBe UmlMetaclass.Property
        }

        test("FK targets UmlMetaclass.Association") {
            val s = exposedProfile.stereotype("FK")
            s shouldNotBe null
            s!!.targetMetaclass shouldBe UmlMetaclass.Association
        }

        // ── Test 3: Properties ───────────────────────────────────────────────────

        test("Table has tableName (required) and schema (default 'public')") {
            val table = exposedProfile.stereotype("Table")
            table shouldNotBe null
            table!!.properties.size shouldBe 2

            val tableName = table.properties.first { it.name == "tableName" }
            tableName.required shouldBe true
            tableName.default shouldBe null

            val schema = table.properties.first { it.name == "schema" }
            schema.required shouldBe false
            schema.default shouldBe "public"
        }

        test("Column has columnType and name, both required") {
            val column = exposedProfile.stereotype("Column")
            column shouldNotBe null
            column!!.properties.size shouldBe 2

            val columnType = column.properties.first { it.name == "columnType" }
            columnType.required shouldBe true
            columnType.default shouldBe null

            val name = column.properties.first { it.name == "name" }
            name.required shouldBe true
            name.default shouldBe null
        }

        test("FK has targetTable, required") {
            val fk = exposedProfile.stereotype("FK")
            fk shouldNotBe null
            fk!!.properties.size shouldBe 1
            val targetTable = fk.properties.first()
            targetTable.name shouldBe "targetTable"
            targetTable.required shouldBe true
            targetTable.default shouldBe null
        }

        // ── Test 4: ServiceLoader discovery ─────────────────────────────────────────

        test("exposedProfile is discovered via ProfileRegistry.loadFromClasspath") {
            ProfileRegistry.loadFromClasspath()
            val found = ProfileRegistry.get("dev.kuml.profiles.exposed")
            found shouldNotBe null
            found!!.name shouldBe "Exposed"
            found.stereotype("Table") shouldNotBe null
            found.stereotype("Column") shouldNotBe null
            found.stereotype("FK") shouldNotBe null
        }

        // ── Test 5: Profile metadata ────────────────────────────────────────────────

        test("exposedProfile has correct namespace, version and extends javaEeProfile") {
            exposedProfile.namespace shouldBe "dev.kuml.profiles.exposed"
            exposedProfile.version shouldBe "1.0.0"
            exposedProfile.extendsProfiles shouldBe listOf("dev.kuml.profiles.javaee")
        }

        // ── Test 6: DSL application ─────────────────────────────────────────────────

        test("Table applied via DSL stores entry in appliedStereotypes") {
            val diagram =
                classDiagram("Exposed Test") {
                    applyProfile(exposedProfile)
                    classOf("User") {
                        stereotype("Table") {
                            "tableName" to "users"
                        }
                    }
                }
            val cls = diagram.elements.filterIsInstance<UmlClass>().first()
            cls.appliedStereotypes.size shouldBe 1
            cls.appliedStereotypes.first().stereotypeName shouldBe "Table"
        }
    })
