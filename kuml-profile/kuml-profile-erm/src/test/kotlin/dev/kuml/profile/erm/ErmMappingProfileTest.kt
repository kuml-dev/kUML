package dev.kuml.profile.erm

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
 * V3.4.6 — profile tests for the ERM mapping profile.
 *
 * Covers:
 * 1. Whitelist — exactly 7 stereotypes
 * 2. Target metaclass checks
 * 3. Properties with correct required/default definitions
 * 4. ServiceLoader discovery via ProfileRegistry.loadFromClasspath()
 * 5. Profile metadata — namespace, version, no extends
 * 6. DSL-application smoke test
 */
class ErmMappingProfileTest :
    FunSpec({

        beforeEach { ProfileRegistry.clear() }
        afterEach { ProfileRegistry.clear() }

        // ── Test 1: Whitelist ────────────────────────────────────────────────────

        test("ermMappingProfile has exactly 7 stereotypes") {
            val names = ermMappingProfile.stereotypes.map { it.name }.toSet()
            names shouldContainExactlyInAnyOrder
                setOf("Entity", "Inheritance", "Column", "Id", "Transient", "FK", "JunctionTable")
            ermMappingProfile.stereotypes.size shouldBe 7
        }

        // ── Test 2: Target metaclasses ───────────────────────────────────────────

        test("Entity targets UmlMetaclass.Class") {
            ermMappingProfile.stereotype("Entity")!!.targetMetaclass shouldBe UmlMetaclass.Class
        }

        test("Inheritance targets UmlMetaclass.Class") {
            ermMappingProfile.stereotype("Inheritance")!!.targetMetaclass shouldBe UmlMetaclass.Class
        }

        test("Column targets UmlMetaclass.Property") {
            ermMappingProfile.stereotype("Column")!!.targetMetaclass shouldBe UmlMetaclass.Property
        }

        test("Id targets UmlMetaclass.Property") {
            ermMappingProfile.stereotype("Id")!!.targetMetaclass shouldBe UmlMetaclass.Property
        }

        test("Transient targets UmlMetaclass.Property") {
            ermMappingProfile.stereotype("Transient")!!.targetMetaclass shouldBe UmlMetaclass.Property
        }

        test("FK targets UmlMetaclass.Association") {
            ermMappingProfile.stereotype("FK")!!.targetMetaclass shouldBe UmlMetaclass.Association
        }

        test("JunctionTable targets UmlMetaclass.Association") {
            ermMappingProfile.stereotype("JunctionTable")!!.targetMetaclass shouldBe UmlMetaclass.Association
        }

        // ── Test 3: Properties ───────────────────────────────────────────────────

        test("Entity has tableName (required), schema (default 'public') and kotlinObjectName (optional)") {
            val entity = ermMappingProfile.stereotype("Entity")!!
            entity.properties.size shouldBe 3
            val tableName = entity.properties.first { it.name == "tableName" }
            tableName.required shouldBe true
            tableName.default shouldBe null
            val schema = entity.properties.first { it.name == "schema" }
            schema.required shouldBe false
            schema.default shouldBe "public"
            val kotlinObjectName = entity.properties.first { it.name == "kotlinObjectName" }
            kotlinObjectName.required shouldBe false
            kotlinObjectName.default shouldBe null
        }

        test("Inheritance has strategy (default JOINED) and discriminatorColumn (default dtype)") {
            val inheritance = ermMappingProfile.stereotype("Inheritance")!!
            inheritance.properties.size shouldBe 2
            val strategy = inheritance.properties.first { it.name == "strategy" }
            strategy.required shouldBe false
            strategy.default shouldBe "JOINED"
            val discriminator = inheritance.properties.first { it.name == "discriminatorColumn" }
            discriminator.default shouldBe "dtype"
        }

        test("Column has columnName (required), sqlType/enumType/fkEntity/fkAttribute/nullable/unique with defaults") {
            val column = ermMappingProfile.stereotype("Column")!!
            column.properties.size shouldBe 7
            val columnName = column.properties.first { it.name == "columnName" }
            columnName.required shouldBe true
            columnName.default shouldBe null
            val sqlType = column.properties.first { it.name == "sqlType" }
            sqlType.default shouldBe ""
            val enumType = column.properties.first { it.name == "enumType" }
            enumType.required shouldBe false
            enumType.default shouldBe null
            val fkEntity = column.properties.first { it.name == "fkEntity" }
            fkEntity.required shouldBe false
            fkEntity.default shouldBe null
            val fkAttribute = column.properties.first { it.name == "fkAttribute" }
            fkAttribute.required shouldBe false
            fkAttribute.default shouldBe null
            val nullable = column.properties.first { it.name == "nullable" }
            nullable.default shouldBe true
            val unique = column.properties.first { it.name == "unique" }
            unique.default shouldBe false
        }

        test("Id has autoIncrement default true") {
            val id = ermMappingProfile.stereotype("Id")!!
            id.properties.size shouldBe 1
            id.properties.first().default shouldBe true
        }

        test("Transient has no properties") {
            ermMappingProfile.stereotype("Transient")!!.properties.size shouldBe 0
        }

        test("FK has constraintName/onDelete/onUpdate, all with defaults") {
            val fk = ermMappingProfile.stereotype("FK")!!
            fk.properties.size shouldBe 3
            fk.properties.first { it.name == "onDelete" }.default shouldBe "NO_ACTION"
            fk.properties.first { it.name == "onUpdate" }.default shouldBe "NO_ACTION"
        }

        test("JunctionTable has tableName (required), sourceColumn/targetColumn with defaults") {
            val junction = ermMappingProfile.stereotype("JunctionTable")!!
            junction.properties.size shouldBe 3
            val tableName = junction.properties.first { it.name == "tableName" }
            tableName.required shouldBe true
            tableName.default shouldBe null
        }

        // ── Test 4: ServiceLoader discovery ──────────────────────────────────────

        test("ermMappingProfile is discovered via ProfileRegistry.loadFromClasspath") {
            ProfileRegistry.loadFromClasspath()
            val found = ProfileRegistry.get(ErmProfileNames.NAMESPACE)
            found shouldNotBe null
            found!!.name shouldBe "ErmMapping"
            found.stereotype("Entity") shouldNotBe null
            found.stereotype("JunctionTable") shouldNotBe null
        }

        // ── Test 5: Profile metadata ─────────────────────────────────────────────

        test("ermMappingProfile has correct namespace, version, and no extends") {
            ermMappingProfile.namespace shouldBe "dev.kuml.profiles.erm"
            ermMappingProfile.version shouldBe "1.0.0"
            ermMappingProfile.extendsProfiles shouldBe emptyList()
        }

        // ── Test 6: DSL application ──────────────────────────────────────────────

        test("Entity applied via DSL stores entry in appliedStereotypes") {
            val diagram =
                classDiagram("ERM Mapping Test") {
                    applyProfile(ermMappingProfile)
                    classOf("Customer") {
                        stereotype("Entity") {
                            "tableName" to "customers"
                        }
                    }
                }
            val cls = diagram.elements.filterIsInstance<UmlClass>().first()
            cls.appliedStereotypes.size shouldBe 1
            cls.appliedStereotypes.first().stereotypeName shouldBe "Entity"
        }
    })
