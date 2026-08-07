package dev.kuml.codegen.m2m.exposed

import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.erm.dsl.ermModel
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmForeignKey
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ReferentialAction
import dev.kuml.erm.model.RelationshipKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * V3.4.8 — core rendering coverage for [ErmExposedEmitter], exercised through
 * [ErmToExposedTransformer] (the ERM-direct M2M path). [ErmExposedGeneratorTest]
 * and the UML-direct chain test cover the other two entry points with the same
 * underlying emitter, so behaviour asserted once here does not need to be
 * re-verified from those paths.
 */
class ErmToExposedTransformerTest :
    FunSpec({

        val transformer = ErmToExposedTransformer()

        fun transform(
            model: ErmModel,
            options: Map<String, String> = emptyMap(),
        ) = transformer.transform(source = model, ctx = TransformContext(options))

        fun successFiles(
            model: ErmModel,
            options: Map<String, String> = emptyMap(),
        ): List<GeneratedFile> = transform(model, options).shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output

        // ── Basics ───────────────────────────────────────────────────────────

        test("basic entity produces a Table object with PrimaryKey") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "first_name", type = ErmDataType.Varchar(255), nullable = false)
                    }
                }
            val files = successFiles(model)
            files shouldHaveSize 1
            val content = files[0].content
            files[0].relativePath shouldBe "Users.kt"
            content shouldContain "public object Users : Table(\"users\")"
            content shouldContain "override val primaryKey: PrimaryKey = PrimaryKey(id)"
            content shouldContain "val firstName: Column<String> = varchar(\"first_name\", 255)"
        }

        test("snake_case entity and attribute names convert to PascalCase/camelCase Kotlin identifiers") {
            val model =
                ermModel(name = "M") {
                    entity(name = "order_items") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "unit_price", type = ErmDataType.Decimal(precision = 10, scale = 2), nullable = false)
                    }
                }
            val content = successFiles(model)[0].content
            content shouldContain "public object OrderItems : Table(\"order_items\")"
            content shouldContain "val unitPrice: Column<BigDecimal> = decimal(\"unit_price\", 10, 2)"
            content shouldContain "import java.math.BigDecimal"
        }

        test("--package option controls the generated package declaration") {
            val model = ermModel(name = "M") { entity(name = "users") { id() } }
            val content = successFiles(model, mapOf("package" to "org.myapp.tables"))[0].content
            content shouldContain "package org.myapp.tables"
        }

        test("default package is com.example.tables") {
            val model = ermModel(name = "M") { entity(name = "users") { id() } }
            successFiles(model)[0].content shouldContain "package com.example.tables"
        }

        // ── Type mapping ─────────────────────────────────────────────────────

        test("every ErmDataType variant maps to the correct Exposed column call") {
            val model =
                ermModel(name = "M") {
                    entity(name = "widgets") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "small_count", type = ErmDataType.Integer(16))
                        attribute(name = "big_count", type = ErmDataType.Integer(64))
                        attribute(name = "price", type = ErmDataType.Decimal(precision = 10, scale = 2))
                        attribute(name = "weight", type = ErmDataType.Real(double = true))
                        attribute(name = "ratio", type = ErmDataType.Real(double = false))
                        attribute(name = "code", type = ErmDataType.Varchar(64))
                        attribute(name = "description", type = ErmDataType.Text)
                        attribute(name = "active", type = ErmDataType.Boolean)
                        attribute(name = "released_on", type = ErmDataType.Date)
                        attribute(name = "daily_at", type = ErmDataType.Time)
                        attribute(name = "created_at", type = ErmDataType.Timestamp())
                        attribute(name = "external_ref", type = ErmDataType.Uuid)
                        attribute(name = "blob_data", type = ErmDataType.Blob)
                        attribute(name = "payload", type = ErmDataType.Json)
                        attribute(name = "geom", type = ErmDataType.Custom("tsvector"))
                    }
                }
            val content = successFiles(model)[0].content

            content shouldContain "val smallCount: Column<Short?> = short(\"small_count\").nullable()"
            content shouldContain "val bigCount: Column<Long?> = long(\"big_count\").nullable()"
            content shouldContain "val price: Column<BigDecimal?> = decimal(\"price\", 10, 2).nullable()"
            content shouldContain "val weight: Column<Double?> = double(\"weight\").nullable()"
            content shouldContain "val ratio: Column<Float?> = float(\"ratio\").nullable()"
            content shouldContain "val code: Column<String?> = varchar(\"code\", 64).nullable()"
            content shouldContain "val description: Column<String?> = text(\"description\").nullable()"
            content shouldContain "val active: Column<Boolean?> = bool(\"active\").nullable()"
            content shouldContain "val releasedOn: Column<LocalDate?> = date(\"released_on\").nullable()"
            content shouldContain "val dailyAt: Column<LocalTime?> = time(\"daily_at\").nullable()"
            content shouldContain "val createdAt: Column<LocalDateTime?> = datetime(\"created_at\").nullable()"
            content shouldContain "val externalRef: Column<UUID?> = javaUUID(\"external_ref\").nullable()"
            content shouldContain "val blobData: Column<ExposedBlob?> = blob(\"blob_data\").nullable()"
            content shouldContain "val payload: Column<String?> = text(\"payload\").nullable() // ErmDataType.Json fallback"
            content shouldContain "val geom: Column<String?> = text(\"geom\").nullable() // Custom(tsvector) fallback"

            content shouldContain "import org.jetbrains.exposed.v1.javatime.date"
            content shouldContain "import org.jetbrains.exposed.v1.javatime.time"
            content shouldContain "import org.jetbrains.exposed.v1.javatime.datetime"
            content shouldContain "import java.time.LocalDate"
            content shouldContain "import java.time.LocalTime"
            content shouldContain "import java.time.LocalDateTime"
            content shouldContain "import java.util.UUID"
            content shouldContain "import org.jetbrains.exposed.v1.core.java.javaUUID"
            content shouldContain "import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob"
        }

        // ── Modifiers ────────────────────────────────────────────────────────

        test("autoIncrement, nullable, unique modifiers render correctly") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        attribute(name = "id", type = ErmDataType.Integer(64), primaryKey = true, nullable = false, autoIncrement = true)
                        attribute(name = "email", type = ErmDataType.Varchar(255), nullable = false, unique = true)
                        attribute(name = "nickname", type = ErmDataType.Varchar(255), nullable = true)
                    }
                }
            val content = successFiles(model)[0].content
            content shouldContain "val id: Column<Long> = long(\"id\").autoIncrement()"
            content shouldContain "val email: Column<String> = varchar(\"email\", 255).uniqueIndex()"
            content shouldContain "val nickname: Column<String?> = varchar(\"nickname\", 255).nullable()"
        }

        test("autoIncrement is ignored for non-Integer types") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        attribute(name = "id", type = ErmDataType.Uuid, primaryKey = true, nullable = false, autoIncrement = true)
                    }
                }
            val content = successFiles(model)[0].content
            content shouldContain "val id: Column<UUID> = javaUUID(\"id\")"
            content shouldNotContain "autoIncrement()"
        }

        test("default value that safely parses for its declared type becomes a typed .default() call") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "credits", type = ErmDataType.Integer(32), nullable = false, default = "0")
                    }
                }
            val content = successFiles(model)[0].content
            content shouldContain ".default(0)"
            content shouldNotContain "// TODO default"
        }

        test("default value that fails to parse for its declared type falls back to a TODO comment") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "credits", type = ErmDataType.Integer(32), nullable = false, default = "not-a-number")
                    }
                }
            val content = successFiles(model)[0].content
            content shouldContain "// TODO default = \"not-a-number\""
            content shouldNotContain ".default("
        }

        // ── Foreign keys ─────────────────────────────────────────────────────

        test("not-null FK attribute becomes reference()") {
            val model =
                ermModel(name = "M") {
                    val authors = entity(name = "authors") { id(name = "id", type = ErmDataType.Integer(64)) }
                    entity(name = "books") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        foreignKey(name = "author_id", references = authors, nullable = false)
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Books.kt" }.content
            // onDelete/onUpdate/fkName are always explicit now (FK constraint naming/action fix) — a bare reference()
            // call left Exposed to compute its own, different, defaults (RESTRICT + a
            // "fk_<table>_<column>__<targetcolumn>" name) that silently disagreed with what
            // ErmSqlEmitter actually wrote to the database. See ermDefaultForeignKeyConstraintName.
            content shouldContain (
                "val authorId: Column<Long> = reference(\"author_id\", Authors.id, " +
                    "onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, " +
                    "fkName = \"fk_books_author_id\")"
            )
        }

        test("nullable FK attribute becomes optReference()") {
            val model =
                ermModel(name = "M") {
                    val authors = entity(name = "authors") { id(name = "id", type = ErmDataType.Integer(64)) }
                    entity(name = "books") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        foreignKey(name = "author_id", references = authors, nullable = true)
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Books.kt" }.content
            content shouldContain (
                "val authorId: Column<Long?> = optReference(\"author_id\", Authors.id, " +
                    "onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, " +
                    "fkName = \"fk_books_author_id\")"
            )
        }

        test("onDelete/onUpdate referential actions render as ReferenceOption named arguments") {
            val model =
                ermModel(name = "M") {
                    val authors = entity(name = "authors") { id(name = "id", type = ErmDataType.Integer(64)) }
                    entity(name = "books") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        foreignKey(
                            name = "author_id",
                            references = authors,
                            nullable = false,
                            onDelete = ReferentialAction.CASCADE,
                        )
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Books.kt" }.content
            content shouldContain (
                "reference(\"author_id\", Authors.id, onDelete = ReferenceOption.CASCADE, " +
                    "onUpdate = ReferenceOption.NO_ACTION, fkName = \"fk_books_author_id\")"
            )
            content shouldContain "import org.jetbrains.exposed.v1.core.ReferenceOption"
        }

        // Renamed from "NO_ACTION referential action omits ReferenceOption arguments entirely"
        // (FK constraint naming/action fix): the old behaviour — a bare reference() call with no onDelete/onUpdate/
        // fkName args at all for NO_ACTION — is exactly the bug this fix closes. A bare call left
        // Exposed's own ForeignKeyConstraint to compute its own defaults at runtime (Postgres
        // dialect default reference option RESTRICT, not NO_ACTION, plus a
        // "fk_<table>_<column>__<targetcolumn>" constraint name), which silently disagreed with
        // what ErmSqlEmitter's DDL — the thing Flyway actually applies to the real database —
        // already declared (NO_ACTION via an omitted clause, name "fk_<table>_<column>"). Now
        // NO_ACTION renders explicitly instead of being left implicit.
        test("NO_ACTION referential action renders explicit ReferenceOption.NO_ACTION arguments") {
            val model =
                ermModel(name = "M") {
                    val authors = entity(name = "authors") { id(name = "id", type = ErmDataType.Integer(64)) }
                    entity(name = "books") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        foreignKey(name = "author_id", references = authors, nullable = false)
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Books.kt" }.content
            content shouldContain (
                "reference(\"author_id\", Authors.id, onDelete = ReferenceOption.NO_ACTION, " +
                    "onUpdate = ReferenceOption.NO_ACTION, fkName = \"fk_books_author_id\")"
            )
            content shouldContain "import org.jetbrains.exposed.v1.core.ReferenceOption"
        }

        // ── FK target-column resolution (targetAttributeId) ─────────────────

        test("FK with explicit targetAttributeId references that column instead of the primary key") {
            lateinit var isbnAttrId: String
            val model =
                ermModel(name = "M") {
                    val authors =
                        entity(name = "authors") {
                            id(name = "id", type = ErmDataType.Integer(64))
                            isbnAttrId = attribute(name = "isbn", type = ErmDataType.Varchar(20), nullable = false, unique = true)
                        }
                    entity(name = "books") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "author_isbn_ref",
                            type = ErmDataType.Varchar(20),
                            nullable = false,
                            foreignKey = ErmForeignKey(targetEntityId = authors, targetAttributeId = isbnAttrId),
                        )
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Books.kt" }.content
            content shouldContain (
                "val authorIsbnRef: Column<String> = reference(\"author_isbn_ref\", Authors.isbn, " +
                    "onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, " +
                    "fkName = \"fk_books_author_isbn_ref\")"
            )
        }

        test("FK without targetAttributeId targeting an entity with a composite primary key fails the transform") {
            val model =
                ermModel(name = "M") {
                    val students = entity(name = "students") { id(name = "id", type = ErmDataType.Uuid) }
                    val courses = entity(name = "courses") { id(name = "id", type = ErmDataType.Uuid) }
                    val studentsCourses =
                        entity(name = "students_courses", weak = true) {
                            attribute(
                                name = "student_id",
                                type = ErmDataType.Uuid,
                                primaryKey = true,
                                nullable = false,
                                foreignKey = ErmForeignKey(targetEntityId = students),
                            )
                            attribute(
                                name = "course_id",
                                type = ErmDataType.Uuid,
                                primaryKey = true,
                                nullable = false,
                                foreignKey = ErmForeignKey(targetEntityId = courses),
                            )
                        }
                    entity(name = "enrollment_notes") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "link_id",
                            type = ErmDataType.Uuid,
                            nullable = false,
                            // No targetAttributeId — studentsCourses has a composite PK, so there is
                            // no unambiguous single target column to fall back to.
                            foreignKey = ErmForeignKey(targetEntityId = studentsCourses),
                        )
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("FK without targetAttributeId targeting a weak entity with no primary key fails the transform") {
            val model =
                ermModel(name = "M") {
                    val users = entity(name = "users") { id(name = "id", type = ErmDataType.Integer(64)) }
                    val auditLog =
                        entity(name = "audit_log", weak = true) {
                            attribute(name = "message", type = ErmDataType.Text)
                        }
                    // Required so audit_log's empty primary key itself passes ErmConstraintChecker.
                    relationship(from = users, to = auditLog, kind = RelationshipKind.IDENTIFYING)
                    entity(name = "audit_log_comments") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "audit_log_ref",
                            type = ErmDataType.Integer(64),
                            nullable = false,
                            // No targetAttributeId — audit_log has no primary key of its own at all.
                            foreignKey = ErmForeignKey(targetEntityId = auditLog),
                        )
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("self-referential FK emits a plain typed column, not reference()") {
            val model =
                ermModel(name = "M") {
                    entity(name = "employees") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "manager_id",
                            type = ErmDataType.Integer(64),
                            nullable = true,
                            // "entity_0" == this entity's own auto-id (first entity() call in the model).
                            foreignKey = ErmForeignKey(targetEntityId = "entity_0"),
                        )
                    }
                }
            val content = successFiles(model)[0].content
            content shouldContain "val managerId: Column<Long?> = long(\"manager_id\").nullable()"
            content shouldContain "self-referential FK"
            content shouldNotContain "reference(\"manager_id\""
            content shouldNotContain "optReference(\"manager_id\""
        }

        // ── Primary keys ─────────────────────────────────────────────────────

        test("composite primary key (junction entity) renders PrimaryKey with both columns") {
            val model =
                ermModel(name = "M") {
                    val students = entity(name = "students") { id(name = "id", type = ErmDataType.Uuid) }
                    val courses = entity(name = "courses") { id(name = "id", type = ErmDataType.Uuid) }
                    entity(name = "students_courses", weak = true) {
                        attribute(
                            name = "student_id",
                            type = ErmDataType.Uuid,
                            primaryKey = true,
                            nullable = false,
                            foreignKey = ErmForeignKey(targetEntityId = students),
                        )
                        attribute(
                            name = "course_id",
                            type = ErmDataType.Uuid,
                            primaryKey = true,
                            nullable = false,
                            foreignKey = ErmForeignKey(targetEntityId = courses),
                        )
                    }
                }
            val content = successFiles(model).first { it.relativePath == "StudentsCourses.kt" }.content
            content shouldContain "public object StudentsCourses : Table(\"students_courses\")"
            content shouldContain "override val primaryKey: PrimaryKey = PrimaryKey(studentId, courseId)"
            content shouldContain (
                "reference(\"student_id\", Students.id, onDelete = ReferenceOption.NO_ACTION, " +
                    "onUpdate = ReferenceOption.NO_ACTION, fkName = \"fk_students_courses_student_id\")"
            )
            content shouldContain (
                "reference(\"course_id\", Courses.id, onDelete = ReferenceOption.NO_ACTION, " +
                    "onUpdate = ReferenceOption.NO_ACTION, fkName = \"fk_students_courses_course_id\")"
            )
        }

        test("weak entity with no primary key omits the primaryKey override") {
            val model =
                ermModel(name = "M") {
                    val users = entity(name = "users") { id(name = "id", type = ErmDataType.Integer(64)) }
                    val auditLog =
                        entity(name = "audit_log", weak = true) {
                            attribute(name = "message", type = ErmDataType.Text)
                        }
                    // ErmConstraintChecker requires a weak entity with an empty primary key to be
                    // the target of an identifying relationship — declare one to keep this model valid.
                    relationship(from = users, to = auditLog, kind = RelationshipKind.IDENTIFYING)
                }
            val content = successFiles(model).first { it.relativePath == "AuditLog.kt" }.content
            content shouldNotContain "override val primaryKey"
            content shouldContain "Weak entity with no primary key"
        }

        // ── Adversarial / identifier safety (defense-in-depth) ──────────────

        test("entity name with characters invalid in a Kotlin identifier fails the transform") {
            // Note: entity object names are always PascalCased (first letter capitalized), so an entity
            // name can never collide with a Kotlin hard keyword (all lowercase) — unlike attribute names,
            // which are camelCased (first letter lowercase, see the keyword test below). Hyphens exercise
            // the identifier-grammar defense on the entity-name path instead.
            val model = ermModel(name = "M") { entity(name = "user-table") { id() } }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("Kotlin-keyword attribute name fails the transform") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id()
                        attribute(name = "class", type = ErmDataType.Varchar(255))
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("attribute name with string-literal breakout characters fails the transform") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id()
                        attribute(name = "evil\") { //", type = ErmDataType.Varchar(255))
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("attribute name with dollar-interpolation characters fails the transform") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id()
                        attribute(name = "bad\$name", type = ErmDataType.Varchar(255))
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("attribute name with backslash/newline fails the transform") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id()
                        attribute(name = "bad\\name\n", type = ErmDataType.Varchar(255))
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("entity name with path-traversal characters fails the transform") {
            val model = ermModel(name = "M") { entity(name = "../../etc/passwd") { id() } }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("structural error — FK targeting a non-existent entity — fails via ErmConstraintChecker") {
            val model =
                ermModel(name = "M") {
                    entity(name = "books") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "author_id",
                            type = ErmDataType.Integer(64),
                            nullable = false,
                            foreignKey = ErmForeignKey(targetEntityId = "does-not-exist"),
                        )
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("model with no entities fails via ErmConstraintChecker") {
            val model = ermModel(name = "Empty") {}
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        // ── Traceability ─────────────────────────────────────────────────────

        test("trace links entity to its generated file and FK attribute with the FK rule id") {
            val model =
                ermModel(name = "M") {
                    val authors = entity(name = "authors") { id(name = "id", type = ErmDataType.Integer(64)) }
                    entity(name = "books") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        foreignKey(name = "author_id", references = authors, nullable = false)
                    }
                }
            val result = transform(model).shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>()
            val entityLink =
                result.trace.links.first { it.ruleId == ErmExposedEmitter.RULE_ENTITY_TO_TABLE && it.targetArtifactId == "Books.kt" }
            entityLink.sourceElementId shouldBe "entity_1"
            val fkLink = result.trace.links.first { it.ruleId == ErmExposedEmitter.RULE_FK_TO_REFERENCE }
            fkLink.targetArtifactId shouldBe "Books.kt"
        }

        // ── PostGIS geometry columns (ADR-0016 §2.3) ─────────────────────────

        test("a recognized PostGIS geometry Custom column renders geometry(...) and emits a support file") {
            val model =
                ermModel(name = "M") {
                    entity(name = "places") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "location", type = ErmDataType.Custom("geometry(Point,4326)"), nullable = false)
                    }
                }
            val files = successFiles(model)
            files.map { it.relativePath } shouldContain "PostGisColumnTypes.kt"

            val placesContent = files.first { it.relativePath == "Places.kt" }.content
            placesContent shouldContain "val location: Column<String> = geometry(\"location\", \"geometry(Point,4326)\")"

            val supportContent = files.first { it.relativePath == "PostGisColumnTypes.kt" }.content
            supportContent shouldContain "package com.example.tables"
            supportContent shouldContain "private class GeometryColumnType(private val sql: String) : ColumnType<String>()"
            supportContent shouldContain "public fun Table.geometry(name: String, sqlType: String): Column<String> ="
        }

        test("a model with no geometry column does not emit the PostGIS support file") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "name", type = ErmDataType.Varchar(255))
                    }
                }
            successFiles(model).map { it.relativePath } shouldNotContain "PostGisColumnTypes.kt"
        }

        test("an unrecognized Custom column still falls back to text() with the explanatory comment") {
            val model =
                ermModel(name = "M") {
                    entity(name = "widgets") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "payload", type = ErmDataType.Custom("tsvector"))
                    }
                }
            val files = successFiles(model)
            files.map { it.relativePath } shouldNotContain "PostGisColumnTypes.kt"
            files[0].content shouldContain "val payload: Column<String?> = text(\"payload\").nullable() // Custom(tsvector) fallback"
        }

        test("a nullable recognized geometry column still gets .nullable() after the geometry(...) call") {
            val model =
                ermModel(name = "M") {
                    entity(name = "places") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "location", type = ErmDataType.Custom("geometry(polygon)"), nullable = true)
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Places.kt" }.content
            content shouldContain
                "val location: Column<String?> = geometry(\"location\", \"geometry(Polygon)\").nullable()"
        }

        test("a geometry column with a Kotlin string literal collision in colLiteral is still safely escaped") {
            // colLiteral is derived from the attribute name (already validated as a safe Kotlin
            // identifier by requireValidKotlinIdentifier before this point) — this test only pins
            // the exact geometry(...) call shape for a plain valid name.
            val model =
                ermModel(name = "M") {
                    entity(name = "places") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "geom", type = ErmDataType.Custom("GEOMETRY(LineString, 3857)"), nullable = false)
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Places.kt" }.content
            content shouldContain "val geom: Column<String> = geometry(\"geom\", \"geometry(LineString,3857)\")"
        }

        // ── TimescaleDB hypertable parity note (ADR-0016 §2.3) ───────────────

        test("hypertable() marker emits an explanatory note comment, no functional Exposed change") {
            val model =
                ermModel(name = "M") {
                    entity(name = "sensor_readings") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "recorded_at", type = ErmDataType.Timestamp(), nullable = false)
                        hypertable(timeColumn = "recorded_at", chunkInterval = "7 days")
                    }
                }
            val content = successFiles(model)[0].content
            content shouldContain
                "// Note: entity marked as TimescaleDB hypertable — emitted only in SQL DDL, not in Exposed."
            content shouldNotContain "create_hypertable"
        }

        test("no hypertable() marker produces no hypertable note comment") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") { id(name = "id", type = ErmDataType.Integer(64)) }
                }
            successFiles(model)[0].content shouldNotContain "TimescaleDB hypertable"
        }

        // ── kotlinObjectName() override (ERM Metadata Retrofit) ──────────────

        test("kotlinObjectName() override replaces the mechanically-derived Kotlin object name") {
            val model =
                ermModel(name = "M") {
                    entity(name = "member") {
                        kotlinObjectName("MemberTable")
                        id()
                    }
                }
            val files = successFiles(model)
            files[0].relativePath shouldBe "MemberTable.kt"
            files[0].content shouldContain "public object MemberTable : Table(\"member\")"
        }

        test("no kotlinObjectName() override falls back to PascalCase derivation (unchanged behaviour)") {
            val model =
                ermModel(name = "M") {
                    entity(name = "member") { id() }
                }
            val files = successFiles(model)
            files[0].relativePath shouldBe "Member.kt"
            files[0].content shouldContain "public object Member : Table(\"member\")"
        }

        test("kotlinObjectName() override propagates to foreign-key reference() calls on other entities") {
            val model =
                ermModel(name = "M") {
                    val authors =
                        entity(name = "authors") {
                            kotlinObjectName("AuthorsTable")
                            id(name = "id", type = ErmDataType.Integer(64))
                        }
                    entity(name = "books") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        foreignKey(name = "author_id", references = authors, nullable = false)
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Books.kt" }.content
            content shouldContain "AuthorsTable.id"
        }

        test("kotlinObjectName() override that is not a valid Kotlin identifier fails the transform") {
            val model =
                ermModel(name = "M") {
                    entity(name = "member") {
                        kotlinObjectName("123-bad")
                        id()
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("kotlinObjectName() override that is a Kotlin hard keyword fails the transform") {
            val model =
                ermModel(name = "M") {
                    entity(name = "member") {
                        kotlinObjectName("object")
                        id()
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("two entities overriding to the same kotlinObjectName collide and fail the transform") {
            val model =
                ermModel(name = "M") {
                    entity(name = "member") {
                        kotlinObjectName("SharedName")
                        id()
                    }
                    entity(name = "account") {
                        kotlinObjectName("SharedName")
                        id()
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("kotlinObjectName() override colliding with an existing enum's Kotlin object name fails the transform") {
            val model =
                ermModel(name = "M") {
                    entity(name = "member") {
                        kotlinObjectName("Status")
                        id()
                    }
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type = ErmDataType.Enum(name = "Status", values = listOf("Active", "Inactive")),
                            nullable = false,
                        )
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("kotlinObjectName() override with path-traversal characters fails the transform") {
            val model =
                ermModel(name = "M") {
                    entity(name = "member") {
                        kotlinObjectName("../evil")
                        id()
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        // ── Enum columns (ADR-0016 retrofit) ─────────────────────────────────

        test("enum attribute generates a second enum class file and an enumerationByName column") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type = ErmDataType.Enum(name = "Status", values = listOf("Active", "Inactive")),
                            nullable = false,
                        )
                    }
                }
            val files = successFiles(model)
            files.map { it.relativePath } shouldContain "Status.kt"
            files.map { it.relativePath } shouldContain "Users.kt"

            val statusContent = files.first { it.relativePath == "Status.kt" }.content
            statusContent shouldContain "public enum class Status {"
            statusContent shouldContain "    Active,"
            statusContent shouldContain "    Inactive,"

            val usersContent = files.first { it.relativePath == "Users.kt" }.content
            usersContent shouldContain "val status: Column<Status> = enumerationByName<Status>(\"status\", 8)"
            // Auto-derived CHECK, named to match Postgres's own auto-naming for the SQL side's
            // anonymous CHECK (users_status_check) — see postgresDefaultEnumCheckConstraintName's
            // KDoc for why this must be explicit rather than left to Exposed's own default.
            usersContent shouldContain "check(\"users_status_check\") { status.inList(Status.entries) }"
            usersContent shouldContain "import org.jetbrains.exposed.v1.core.inList"
        }

        test("Enum default that needs customEnumeration's sanitizing skips .default(), still emits the CHECK") {
            // ermEnumNeedsCustomMapping's KDoc: verified against a real Postgres container that
            // Exposed 1.3.1's schema-diff can't recognize a default round-tripped through
            // customEnumeration's toDb lambda — stays on the TODO-comment fallback rather than
            // emit a .default(...) this Exposed version can't keep in sync with itself. The CHECK
            // constraint itself is unaffected (only ever built from the enum's own entries, never
            // from the default), so it's still emitted normally.
            val model =
                ermModel(name = "M") {
                    entity(name = "tickets") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "origin",
                            type = ErmDataType.Enum(name = "TicketOrigin", values = listOf("PROBLEM_REPORT", "CATALOG_BOOKING")),
                            nullable = false,
                            default = "PROBLEM_REPORT",
                        )
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Tickets.kt" }.content
            content shouldContain "// TODO default = \"PROBLEM_REPORT\""
            content shouldNotContain ".default("
            content shouldContain "check(\"tickets_origin_check\") { origin.inList(TicketOrigin.entries) }"
        }

        test("two entities referencing the same enum name/values dedupe to a single enum file") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type = ErmDataType.Enum(name = "Status", values = listOf("Active", "Inactive")),
                            nullable = false,
                        )
                    }
                    entity(name = "accounts") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type = ErmDataType.Enum(name = "Status", values = listOf("Active", "Inactive")),
                            nullable = false,
                        )
                    }
                }
            val files = successFiles(model)
            files.count { it.relativePath == "Status.kt" } shouldBe 1
        }

        test("two ErmDataType.Enum instances with the same name but different values fail the transform") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type = ErmDataType.Enum(name = "Status", values = listOf("Active", "Inactive")),
                            nullable = false,
                        )
                    }
                    entity(name = "accounts") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type = ErmDataType.Enum(name = "Status", values = listOf("Open", "Closed")),
                            nullable = false,
                        )
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("enum name colliding with an entity's Kotlin object name fails the transform") {
            val model =
                ermModel(name = "M") {
                    entity(name = "status") { id(name = "id", type = ErmDataType.Integer(64)) }
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type = ErmDataType.Enum(name = "Status", values = listOf("Active", "Inactive")),
                            nullable = false,
                        )
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("enum literal with spaces is sanitized to a PascalCase constant and uses customEnumeration") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type = ErmDataType.Enum(name = "Status", values = listOf("in progress", "done")),
                            nullable = false,
                        )
                    }
                }
            val files = successFiles(model)
            val statusContent = files.first { it.relativePath == "Status.kt" }.content
            statusContent shouldContain "public enum class Status(public val dbValue: String) {"
            statusContent shouldContain "InProgress(\"in progress\"),"
            statusContent shouldContain "Done(\"done\");"
            statusContent shouldContain "public fun fromDb(value: String): Status = entries.first { it.dbValue == value }"

            val usersContent = files.first { it.relativePath == "Users.kt" }.content
            usersContent shouldContain
                "val status: Column<Status> = customEnumeration<Status>(\"status\", \"VARCHAR(11)\", " +
                "{ Status.fromDb(it as String) }, { it.dbValue })"
        }

        test("enum literal with no alphanumeric characters fails the transform") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type = ErmDataType.Enum(name = "Status", values = listOf("---", "done")),
                            nullable = false,
                        )
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("two enum literals sanitizing to the same Kotlin constant name fail the transform") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type = ErmDataType.Enum(name = "Status", values = listOf("In Progress", "In-Progress")),
                            nullable = false,
                        )
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("nullable enum column renders Column<Status?> with .nullable()") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type = ErmDataType.Enum(name = "Status", values = listOf("Active", "Inactive")),
                            nullable = true,
                        )
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Users.kt" }.content
            content shouldContain "val status: Column<Status?> = enumerationByName<Status>(\"status\", 8).nullable()"
        }

        // ── External enum types (enumType retrofit) ─────────────────────────────

        test("externalFqName enum column imports and references the external type, no enum class file") {
            val model =
                ermModel(name = "M") {
                    entity(name = "members") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type =
                                ErmDataType.Enum(
                                    name = "MemberStatus",
                                    values = listOf("Active", "Inactive"),
                                    externalFqName = "network.lapis.cloud.shared.domain.MemberStatus",
                                ),
                            nullable = false,
                        )
                    }
                }
            val files = successFiles(model)
            files.map { it.relativePath } shouldNotContain "MemberStatus.kt"
            files.map { it.relativePath } shouldContain "Members.kt"

            val content = files.first { it.relativePath == "Members.kt" }.content
            content shouldContain "import network.lapis.cloud.shared.domain.MemberStatus"
            content shouldContain
                "val status: Column<MemberStatus> = enumerationByName<MemberStatus>(\"status\", 8)"
            // Never customEnumeration for an external type — its constant names are already fixed.
            content shouldNotContain "customEnumeration"
        }

        test("nullable externalFqName enum column renders Column<T?> with .nullable()") {
            val model =
                ermModel(name = "M") {
                    entity(name = "members") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type =
                                ErmDataType.Enum(
                                    name = "MemberStatus",
                                    values = listOf("Active", "Inactive"),
                                    externalFqName = "network.lapis.cloud.shared.domain.MemberStatus",
                                ),
                            nullable = true,
                        )
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Members.kt" }.content
            content shouldContain
                "val status: Column<MemberStatus?> = enumerationByName<MemberStatus>(\"status\", 8).nullable()"
        }

        test("two entities referencing the same externalFqName enum dedupe to zero enum files") {
            val model =
                ermModel(name = "M") {
                    entity(name = "members") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type =
                                ErmDataType.Enum(
                                    name = "MemberStatus",
                                    values = listOf("Active", "Inactive"),
                                    externalFqName = "network.lapis.cloud.shared.domain.MemberStatus",
                                ),
                            nullable = false,
                        )
                    }
                    entity(name = "accounts") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type =
                                ErmDataType.Enum(
                                    name = "MemberStatus",
                                    values = listOf("Active", "Inactive"),
                                    externalFqName = "network.lapis.cloud.shared.domain.MemberStatus",
                                ),
                            nullable = false,
                        )
                    }
                }
            val files = successFiles(model)
            files.count { it.relativePath == "MemberStatus.kt" } shouldBe 0
        }

        test("same enum name with externalFqName set on one attribute and null on another fails the transform") {
            val model =
                ermModel(name = "M") {
                    entity(name = "members") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type =
                                ErmDataType.Enum(
                                    name = "MemberStatus",
                                    values = listOf("Active", "Inactive"),
                                    externalFqName = "network.lapis.cloud.shared.domain.MemberStatus",
                                ),
                            nullable = false,
                        )
                    }
                    entity(name = "accounts") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type = ErmDataType.Enum(name = "MemberStatus", values = listOf("Active", "Inactive")),
                            nullable = false,
                        )
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("externalFqName enum column does not collide with an entity of the same simple name") {
            // MemberStatus is only referenced as an import target, never as a generated file —
            // an entity named "member_status" must not trip the duplicate-object-name guard.
            val model =
                ermModel(name = "M") {
                    entity(name = "member_status") { id(name = "id", type = ErmDataType.Integer(64)) }
                    entity(name = "members") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(
                            name = "status",
                            type =
                                ErmDataType.Enum(
                                    name = "MemberStatus",
                                    values = listOf("Active", "Inactive"),
                                    externalFqName = "network.lapis.cloud.shared.domain.MemberStatus",
                                ),
                            nullable = false,
                        )
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>()
        }

        // ── Partial/conditional indexes (V3.4.11 — comment-only, not emitted as Exposed DSL) ──

        test("a partial (WHERE-carrying) index is documented in the not-emitted comment with its predicate") {
            val model =
                ermModel(name = "M") {
                    entity(name = "invitations") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "consumed_at", type = ErmDataType.Timestamp(), nullable = true)
                        index("consumed_at", unique = true, name = "idx_invitations_pending", where = "consumed_at IS NULL")
                    }
                }
            val content = successFiles(model)[0].content
            content shouldContain "// Note: 1 index(es) declared on this entity are not emitted (1 partial, with a WHERE predicate) —"
            content shouldContain "idx_invitations_pending: WHERE consumed_at IS NULL"
            // Still comment-only — no fabricated .uniqueIndex()/index{} call for this index (the word
            // "filterCondition" legitimately appears in the explanatory comment text above).
            content shouldNotContain ".uniqueIndex()"
            content shouldNotContain "= index("
        }

        test("a non-partial index does not mention a WHERE predicate in the not-emitted comment") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "email", type = ErmDataType.Varchar(255))
                        index("email", name = "idx_users_email")
                    }
                }
            val content = successFiles(model)[0].content
            content shouldContain "// Note: 1 index(es) declared on this entity are not emitted —"
            content shouldNotContain "partial"
            content shouldNotContain "WHERE"
        }

        // ── uuidRepresentation option ────────────────────────────────────────

        test("no uuidRepresentation option set renders javaUUID/UUID (unchanged default behaviour)") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Uuid)
                    }
                }
            val content = successFiles(model)[0].content
            content shouldContain "val id: Column<UUID> = javaUUID(\"id\")"
            content shouldContain "import java.util.UUID"
            content shouldContain "import org.jetbrains.exposed.v1.core.java.javaUUID"
            content shouldNotContain "kotlin.uuid.Uuid"
        }

        test("uuidRepresentation = \"java\" (explicit) renders javaUUID/UUID, same as the default") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Uuid)
                    }
                }
            val content = successFiles(model, mapOf("uuidRepresentation" to "java"))[0].content
            content shouldContain "val id: Column<UUID> = javaUUID(\"id\")"
            content shouldContain "import java.util.UUID"
            content shouldContain "import org.jetbrains.exposed.v1.core.java.javaUUID"
        }

        test("uuidRepresentation = \"kotlin\" renders uuid(...)/Column<Uuid> with kotlin.uuid.Uuid import") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Uuid)
                        attribute(name = "external_ref", type = ErmDataType.Uuid, nullable = true)
                    }
                }
            val content = successFiles(model, mapOf("uuidRepresentation" to "kotlin"))[0].content
            content shouldContain "val id: Column<Uuid> = uuid(\"id\")"
            content shouldContain "val externalRef: Column<Uuid?> = uuid(\"external_ref\").nullable()"
            content shouldContain "import kotlin.uuid.Uuid"
            content shouldNotContain "javaUUID"
            content shouldNotContain "java.util.UUID"
        }

        test("uuidRepresentation = \"kotlin\": FK reference() to a Uuid primary key renders Column<Uuid>") {
            val model =
                ermModel(name = "M") {
                    val authors = entity(name = "authors") { id(name = "id", type = ErmDataType.Uuid) }
                    entity(name = "books") {
                        id(name = "id", type = ErmDataType.Uuid)
                        foreignKey(name = "author_id", references = authors, nullable = false)
                    }
                }
            val files = successFiles(model, mapOf("uuidRepresentation" to "kotlin"))
            val authorsContent = files.first { it.relativePath == "Authors.kt" }.content
            val booksContent = files.first { it.relativePath == "Books.kt" }.content

            authorsContent shouldContain "val id: Column<Uuid> = uuid(\"id\")"
            authorsContent shouldContain "import kotlin.uuid.Uuid"

            booksContent shouldContain (
                "val authorId: Column<Uuid> = reference(\"author_id\", Authors.id, " +
                    "onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, " +
                    "fkName = \"fk_books_author_id\")"
            )
            booksContent shouldContain "import kotlin.uuid.Uuid"
            booksContent shouldNotContain "javaUUID"
            booksContent shouldNotContain "java.util.UUID"
        }

        test("uuidRepresentation = \"kotlin\": nullable FK optReference() to a Uuid primary key renders Column<Uuid?>") {
            val model =
                ermModel(name = "M") {
                    val authors = entity(name = "authors") { id(name = "id", type = ErmDataType.Uuid) }
                    entity(name = "books") {
                        id(name = "id", type = ErmDataType.Uuid)
                        foreignKey(name = "author_id", references = authors, nullable = true)
                    }
                }
            val booksContent =
                successFiles(model, mapOf("uuidRepresentation" to "kotlin"))
                    .first { it.relativePath == "Books.kt" }
                    .content
            booksContent shouldContain (
                "val authorId: Column<Uuid?> = optReference(\"author_id\", Authors.id, " +
                    "onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, " +
                    "fkName = \"fk_books_author_id\")"
            )
        }

        test("unrecognized uuidRepresentation value falls back to the java default rather than failing") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Uuid)
                    }
                }
            val content = successFiles(model, mapOf("uuidRepresentation" to "bogus-typo"))[0].content
            content shouldContain "val id: Column<UUID> = javaUUID(\"id\")"
            content shouldContain "import java.util.UUID"
        }

        // ── dateTimeRepresentation option ────────────────────────────────────

        test("no dateTimeRepresentation option set renders javatime date/datetime (unchanged default behaviour)") {
            val model =
                ermModel(name = "M") {
                    entity(name = "events") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "released_on", type = ErmDataType.Date, nullable = false)
                        attribute(name = "created_at", type = ErmDataType.Timestamp(), nullable = false)
                    }
                }
            val content = successFiles(model)[0].content
            content shouldContain "val releasedOn: Column<LocalDate> = date(\"released_on\")"
            content shouldContain "val createdAt: Column<LocalDateTime> = datetime(\"created_at\")"
            content shouldContain "import org.jetbrains.exposed.v1.javatime.date"
            content shouldContain "import org.jetbrains.exposed.v1.javatime.datetime"
            content shouldContain "import java.time.LocalDate"
            content shouldContain "import java.time.LocalDateTime"
            content shouldNotContain "kotlinx.datetime"
            content shouldNotContain "org.jetbrains.exposed.v1.datetime."
        }

        test("dateTimeRepresentation = \"java\" (explicit) renders javatime date/datetime, same as the default") {
            val model =
                ermModel(name = "M") {
                    entity(name = "events") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "released_on", type = ErmDataType.Date, nullable = false)
                        attribute(name = "created_at", type = ErmDataType.Timestamp(), nullable = false)
                    }
                }
            val content = successFiles(model, mapOf("dateTimeRepresentation" to "java"))[0].content
            content shouldContain "val releasedOn: Column<LocalDate> = date(\"released_on\")"
            content shouldContain "val createdAt: Column<LocalDateTime> = datetime(\"created_at\")"
            content shouldContain "import org.jetbrains.exposed.v1.javatime.date"
            content shouldContain "import org.jetbrains.exposed.v1.javatime.datetime"
            content shouldContain "import java.time.LocalDate"
            content shouldContain "import java.time.LocalDateTime"
        }

        test(
            "dateTimeRepresentation = \"kotlin\" renders kotlinx-datetime date(...)/datetime(...) with " +
                "kotlinx.datetime imports",
        ) {
            val model =
                ermModel(name = "M") {
                    entity(name = "events") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "released_on", type = ErmDataType.Date, nullable = false)
                        attribute(name = "created_at", type = ErmDataType.Timestamp(), nullable = true)
                    }
                }
            val content = successFiles(model, mapOf("dateTimeRepresentation" to "kotlin"))[0].content
            content shouldContain "val releasedOn: Column<LocalDate> = date(\"released_on\")"
            content shouldContain "val createdAt: Column<LocalDateTime?> = datetime(\"created_at\").nullable()"
            content shouldContain "import org.jetbrains.exposed.v1.datetime.date"
            content shouldContain "import org.jetbrains.exposed.v1.datetime.datetime"
            content shouldContain "import kotlinx.datetime.LocalDate"
            content shouldContain "import kotlinx.datetime.LocalDateTime"
            content shouldNotContain "org.jetbrains.exposed.v1.javatime.date"
            content shouldNotContain "org.jetbrains.exposed.v1.javatime.datetime"
            content shouldNotContain "import java.time.LocalDate"
            content shouldNotContain "import java.time.LocalDateTime"
        }

        test(
            "dateTimeRepresentation = \"instant\" renders timestamp(...)/Column<Instant> with kotlin.time.Instant import",
        ) {
            val model =
                ermModel(name = "M") {
                    entity(name = "events") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "created_at", type = ErmDataType.Timestamp(), nullable = false)
                    }
                }
            val content = successFiles(model, mapOf("dateTimeRepresentation" to "instant"))[0].content
            content shouldContain "val createdAt: Column<Instant> = timestamp(\"created_at\")"
            content shouldContain "import org.jetbrains.exposed.v1.datetime.timestamp"
            content shouldContain "import kotlin.time.Instant"
            content shouldNotContain "datetime("
            content shouldNotContain "LocalDateTime"
        }

        test("dateTimeRepresentation = \"instant\": a Date column falls back to the kotlin (kotlinx.datetime) rendering") {
            val model =
                ermModel(name = "M") {
                    entity(name = "events") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "released_on", type = ErmDataType.Date, nullable = false)
                    }
                }
            val content = successFiles(model, mapOf("dateTimeRepresentation" to "instant"))[0].content
            content shouldContain "val releasedOn: Column<LocalDate> = date(\"released_on\")"
            content shouldContain "import org.jetbrains.exposed.v1.datetime.date"
            content shouldContain "import kotlinx.datetime.LocalDate"
            content shouldNotContain "org.jetbrains.exposed.v1.javatime.date"
            content shouldNotContain "java.time.LocalDate"
        }

        test("dateTimeRepresentation = \"instant\": nullable Timestamp column renders Column<Instant?> with .nullable()") {
            val model =
                ermModel(name = "M") {
                    entity(name = "events") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "created_at", type = ErmDataType.Timestamp(), nullable = true)
                    }
                }
            val content = successFiles(model, mapOf("dateTimeRepresentation" to "instant"))[0].content
            content shouldContain "val createdAt: Column<Instant?> = timestamp(\"created_at\").nullable()"
        }

        test("unrecognized dateTimeRepresentation value falls back to the java default rather than failing") {
            val model =
                ermModel(name = "M") {
                    entity(name = "events") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "released_on", type = ErmDataType.Date, nullable = false)
                    }
                }
            val content = successFiles(model, mapOf("dateTimeRepresentation" to "bogus-typo"))[0].content
            content shouldContain "val releasedOn: Column<LocalDate> = date(\"released_on\")"
            content shouldContain "import java.time.LocalDate"
            content shouldContain "import org.jetbrains.exposed.v1.javatime.date"
        }

        test(
            "uuidRepresentation and dateTimeRepresentation are independently selectable and both apply " +
                "in the same generation run",
        ) {
            val model =
                ermModel(name = "M") {
                    entity(name = "events") {
                        id(name = "id", type = ErmDataType.Uuid)
                        attribute(name = "created_at", type = ErmDataType.Timestamp(), nullable = false)
                    }
                }
            val content =
                successFiles(
                    model,
                    mapOf("uuidRepresentation" to "kotlin", "dateTimeRepresentation" to "kotlin"),
                )[0].content

            content shouldContain "val id: Column<Uuid> = uuid(\"id\")"
            content shouldContain "import kotlin.uuid.Uuid"
            content shouldContain "val createdAt: Column<LocalDateTime> = datetime(\"created_at\")"
            content shouldContain "import org.jetbrains.exposed.v1.datetime.datetime"
            content shouldContain "import kotlinx.datetime.LocalDateTime"
            content shouldNotContain "javaUUID"
            content shouldNotContain "java.util.UUID"
            content shouldNotContain "org.jetbrains.exposed.v1.javatime.datetime"
            content shouldNotContain "import java.time.LocalDateTime"
        }

        test("uuidRepresentation = \"kotlin\" alone leaves Date/Timestamp columns on the javatime default") {
            val model =
                ermModel(name = "M") {
                    entity(name = "events") {
                        id(name = "id", type = ErmDataType.Uuid)
                        attribute(name = "created_at", type = ErmDataType.Timestamp(), nullable = false)
                    }
                }
            val content = successFiles(model, mapOf("uuidRepresentation" to "kotlin"))[0].content

            content shouldContain "val id: Column<Uuid> = uuid(\"id\")"
            content shouldContain "import kotlin.uuid.Uuid"
            content shouldContain "val createdAt: Column<LocalDateTime> = datetime(\"created_at\")"
            content shouldContain "import org.jetbrains.exposed.v1.javatime.datetime"
            content shouldContain "import java.time.LocalDateTime"
            content shouldNotContain "org.jetbrains.exposed.v1.datetime.datetime"
            content shouldNotContain "kotlinx.datetime"
        }

        test("dateTimeRepresentation = \"kotlin\" alone leaves the Uuid column on the javaUUID default") {
            val model =
                ermModel(name = "M") {
                    entity(name = "events") {
                        id(name = "id", type = ErmDataType.Uuid)
                        attribute(name = "created_at", type = ErmDataType.Timestamp(), nullable = false)
                    }
                }
            val content = successFiles(model, mapOf("dateTimeRepresentation" to "kotlin"))[0].content

            content shouldContain "val id: Column<UUID> = javaUUID(\"id\")"
            content shouldContain "import java.util.UUID"
            content shouldContain "import org.jetbrains.exposed.v1.core.java.javaUUID"
            content shouldContain "val createdAt: Column<LocalDateTime> = datetime(\"created_at\")"
            content shouldContain "import org.jetbrains.exposed.v1.datetime.datetime"
            content shouldNotContain "kotlin.uuid.Uuid"
        }
    })
