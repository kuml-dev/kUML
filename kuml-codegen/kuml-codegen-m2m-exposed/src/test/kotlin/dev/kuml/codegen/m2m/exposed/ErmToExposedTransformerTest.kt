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
        ) = transformer.transform(model, TransformContext(options))

        fun successFiles(
            model: ErmModel,
            options: Map<String, String> = emptyMap(),
        ): List<GeneratedFile> = transform(model, options).shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output

        // ── Basics ───────────────────────────────────────────────────────────

        test("basic entity produces a Table object with PrimaryKey") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("first_name", ErmDataType.Varchar(255), nullable = false)
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
                ermModel("M") {
                    entity("order_items") {
                        id("id", ErmDataType.Integer(64))
                        attribute("unit_price", ErmDataType.Decimal(10, 2), nullable = false)
                    }
                }
            val content = successFiles(model)[0].content
            content shouldContain "public object OrderItems : Table(\"order_items\")"
            content shouldContain "val unitPrice: Column<BigDecimal> = decimal(\"unit_price\", 10, 2)"
            content shouldContain "import java.math.BigDecimal"
        }

        test("--package option controls the generated package declaration") {
            val model = ermModel("M") { entity("users") { id() } }
            val content = successFiles(model, mapOf("package" to "org.myapp.tables"))[0].content
            content shouldContain "package org.myapp.tables"
        }

        test("default package is com.example.tables") {
            val model = ermModel("M") { entity("users") { id() } }
            successFiles(model)[0].content shouldContain "package com.example.tables"
        }

        // ── Type mapping ─────────────────────────────────────────────────────

        test("every ErmDataType variant maps to the correct Exposed column call") {
            val model =
                ermModel("M") {
                    entity("widgets") {
                        id("id", ErmDataType.Integer(64))
                        attribute("small_count", ErmDataType.Integer(16))
                        attribute("big_count", ErmDataType.Integer(64))
                        attribute("price", ErmDataType.Decimal(10, 2))
                        attribute("weight", ErmDataType.Real(double = true))
                        attribute("ratio", ErmDataType.Real(double = false))
                        attribute("code", ErmDataType.Varchar(64))
                        attribute("description", ErmDataType.Text)
                        attribute("active", ErmDataType.Boolean)
                        attribute("released_on", ErmDataType.Date)
                        attribute("daily_at", ErmDataType.Time)
                        attribute("created_at", ErmDataType.Timestamp())
                        attribute("external_ref", ErmDataType.Uuid)
                        attribute("blob_data", ErmDataType.Blob)
                        attribute("payload", ErmDataType.Json)
                        attribute("geom", ErmDataType.Custom("tsvector"))
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
                ermModel("M") {
                    entity("users") {
                        attribute("id", ErmDataType.Integer(64), primaryKey = true, nullable = false, autoIncrement = true)
                        attribute("email", ErmDataType.Varchar(255), nullable = false, unique = true)
                        attribute("nickname", ErmDataType.Varchar(255), nullable = true)
                    }
                }
            val content = successFiles(model)[0].content
            content shouldContain "val id: Column<Long> = long(\"id\").autoIncrement()"
            content shouldContain "val email: Column<String> = varchar(\"email\", 255).uniqueIndex()"
            content shouldContain "val nickname: Column<String?> = varchar(\"nickname\", 255).nullable()"
        }

        test("autoIncrement is ignored for non-Integer types") {
            val model =
                ermModel("M") {
                    entity("users") {
                        attribute("id", ErmDataType.Uuid, primaryKey = true, nullable = false, autoIncrement = true)
                    }
                }
            val content = successFiles(model)[0].content
            content shouldContain "val id: Column<UUID> = javaUUID(\"id\")"
            content shouldNotContain "autoIncrement()"
        }

        test("default value is emitted as a TODO comment, not a typed .default() call") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("credits", ErmDataType.Integer(32), nullable = false, default = "0")
                    }
                }
            val content = successFiles(model)[0].content
            content shouldContain "// TODO default = \"0\""
            content shouldNotContain ".default("
        }

        // ── Foreign keys ─────────────────────────────────────────────────────

        test("not-null FK attribute becomes reference()") {
            val model =
                ermModel("M") {
                    val authors = entity("authors") { id("id", ErmDataType.Integer(64)) }
                    entity("books") {
                        id("id", ErmDataType.Integer(64))
                        foreignKey("author_id", references = authors, nullable = false)
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Books.kt" }.content
            content shouldContain "val authorId: Column<Long> = reference(\"author_id\", Authors.id)"
        }

        test("nullable FK attribute becomes optReference()") {
            val model =
                ermModel("M") {
                    val authors = entity("authors") { id("id", ErmDataType.Integer(64)) }
                    entity("books") {
                        id("id", ErmDataType.Integer(64))
                        foreignKey("author_id", references = authors, nullable = true)
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Books.kt" }.content
            content shouldContain "val authorId: Column<Long?> = optReference(\"author_id\", Authors.id)"
        }

        test("onDelete/onUpdate referential actions render as ReferenceOption named arguments") {
            val model =
                ermModel("M") {
                    val authors = entity("authors") { id("id", ErmDataType.Integer(64)) }
                    entity("books") {
                        id("id", ErmDataType.Integer(64))
                        foreignKey(
                            "author_id",
                            references = authors,
                            nullable = false,
                            onDelete = ReferentialAction.CASCADE,
                        )
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Books.kt" }.content
            content shouldContain "reference(\"author_id\", Authors.id, onDelete = ReferenceOption.CASCADE)"
            content shouldContain "import org.jetbrains.exposed.v1.core.ReferenceOption"
        }

        test("NO_ACTION referential action omits ReferenceOption arguments entirely") {
            val model =
                ermModel("M") {
                    val authors = entity("authors") { id("id", ErmDataType.Integer(64)) }
                    entity("books") {
                        id("id", ErmDataType.Integer(64))
                        foreignKey("author_id", references = authors, nullable = false)
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Books.kt" }.content
            content shouldNotContain "ReferenceOption"
            content shouldContain "reference(\"author_id\", Authors.id)"
        }

        // ── FK target-column resolution (targetAttributeId) ─────────────────

        test("FK with explicit targetAttributeId references that column instead of the primary key") {
            lateinit var isbnAttrId: String
            val model =
                ermModel("M") {
                    val authors =
                        entity("authors") {
                            id("id", ErmDataType.Integer(64))
                            isbnAttrId = attribute("isbn", ErmDataType.Varchar(20), nullable = false, unique = true)
                        }
                    entity("books") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "author_isbn_ref",
                            ErmDataType.Varchar(20),
                            nullable = false,
                            foreignKey = ErmForeignKey(targetEntityId = authors, targetAttributeId = isbnAttrId),
                        )
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Books.kt" }.content
            content shouldContain "val authorIsbnRef: Column<String> = reference(\"author_isbn_ref\", Authors.isbn)"
        }

        test("FK without targetAttributeId targeting an entity with a composite primary key fails the transform") {
            val model =
                ermModel("M") {
                    val students = entity("students") { id("id", ErmDataType.Uuid) }
                    val courses = entity("courses") { id("id", ErmDataType.Uuid) }
                    val studentsCourses =
                        entity("students_courses", weak = true) {
                            attribute(
                                "student_id",
                                ErmDataType.Uuid,
                                primaryKey = true,
                                nullable = false,
                                foreignKey = ErmForeignKey(targetEntityId = students),
                            )
                            attribute(
                                "course_id",
                                ErmDataType.Uuid,
                                primaryKey = true,
                                nullable = false,
                                foreignKey = ErmForeignKey(targetEntityId = courses),
                            )
                        }
                    entity("enrollment_notes") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "link_id",
                            ErmDataType.Uuid,
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
                ermModel("M") {
                    val users = entity("users") { id("id", ErmDataType.Integer(64)) }
                    val auditLog =
                        entity("audit_log", weak = true) {
                            attribute("message", ErmDataType.Text)
                        }
                    // Required so audit_log's empty primary key itself passes ErmConstraintChecker.
                    relationship(from = users, to = auditLog, kind = RelationshipKind.IDENTIFYING)
                    entity("audit_log_comments") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "audit_log_ref",
                            ErmDataType.Integer(64),
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
                ermModel("M") {
                    entity("employees") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "manager_id",
                            ErmDataType.Integer(64),
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
                ermModel("M") {
                    val students = entity("students") { id("id", ErmDataType.Uuid) }
                    val courses = entity("courses") { id("id", ErmDataType.Uuid) }
                    entity("students_courses", weak = true) {
                        attribute(
                            "student_id",
                            ErmDataType.Uuid,
                            primaryKey = true,
                            nullable = false,
                            foreignKey = ErmForeignKey(targetEntityId = students),
                        )
                        attribute(
                            "course_id",
                            ErmDataType.Uuid,
                            primaryKey = true,
                            nullable = false,
                            foreignKey = ErmForeignKey(targetEntityId = courses),
                        )
                    }
                }
            val content = successFiles(model).first { it.relativePath == "StudentsCourses.kt" }.content
            content shouldContain "public object StudentsCourses : Table(\"students_courses\")"
            content shouldContain "override val primaryKey: PrimaryKey = PrimaryKey(studentId, courseId)"
            content shouldContain "reference(\"student_id\", Students.id)"
            content shouldContain "reference(\"course_id\", Courses.id)"
        }

        test("weak entity with no primary key omits the primaryKey override") {
            val model =
                ermModel("M") {
                    val users = entity("users") { id("id", ErmDataType.Integer(64)) }
                    val auditLog =
                        entity("audit_log", weak = true) {
                            attribute("message", ErmDataType.Text)
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
            val model = ermModel("M") { entity("user-table") { id() } }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("Kotlin-keyword attribute name fails the transform") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id()
                        attribute("class", ErmDataType.Varchar(255))
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("attribute name with string-literal breakout characters fails the transform") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id()
                        attribute("evil\") { //", ErmDataType.Varchar(255))
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("attribute name with dollar-interpolation characters fails the transform") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id()
                        attribute("bad\$name", ErmDataType.Varchar(255))
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("attribute name with backslash/newline fails the transform") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id()
                        attribute("bad\\name\n", ErmDataType.Varchar(255))
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("entity name with path-traversal characters fails the transform") {
            val model = ermModel("M") { entity("../../etc/passwd") { id() } }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("structural error — FK targeting a non-existent entity — fails via ErmConstraintChecker") {
            val model =
                ermModel("M") {
                    entity("books") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "author_id",
                            ErmDataType.Integer(64),
                            nullable = false,
                            foreignKey = ErmForeignKey(targetEntityId = "does-not-exist"),
                        )
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("model with no entities fails via ErmConstraintChecker") {
            val model = ermModel("Empty") {}
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        // ── Traceability ─────────────────────────────────────────────────────

        test("trace links entity to its generated file and FK attribute with the FK rule id") {
            val model =
                ermModel("M") {
                    val authors = entity("authors") { id("id", ErmDataType.Integer(64)) }
                    entity("books") {
                        id("id", ErmDataType.Integer(64))
                        foreignKey("author_id", references = authors, nullable = false)
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
                ermModel("M") {
                    entity("places") {
                        id("id", ErmDataType.Integer(64))
                        attribute("location", ErmDataType.Custom("geometry(Point,4326)"), nullable = false)
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
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("name", ErmDataType.Varchar(255))
                    }
                }
            successFiles(model).map { it.relativePath } shouldNotContain "PostGisColumnTypes.kt"
        }

        test("an unrecognized Custom column still falls back to text() with the explanatory comment") {
            val model =
                ermModel("M") {
                    entity("widgets") {
                        id("id", ErmDataType.Integer(64))
                        attribute("payload", ErmDataType.Custom("tsvector"))
                    }
                }
            val files = successFiles(model)
            files.map { it.relativePath } shouldNotContain "PostGisColumnTypes.kt"
            files[0].content shouldContain "val payload: Column<String?> = text(\"payload\").nullable() // Custom(tsvector) fallback"
        }

        test("a nullable recognized geometry column still gets .nullable() after the geometry(...) call") {
            val model =
                ermModel("M") {
                    entity("places") {
                        id("id", ErmDataType.Integer(64))
                        attribute("location", ErmDataType.Custom("geometry(polygon)"), nullable = true)
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
                ermModel("M") {
                    entity("places") {
                        id("id", ErmDataType.Integer(64))
                        attribute("geom", ErmDataType.Custom("GEOMETRY(LineString, 3857)"), nullable = false)
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Places.kt" }.content
            content shouldContain "val geom: Column<String> = geometry(\"geom\", \"geometry(LineString,3857)\")"
        }

        // ── TimescaleDB hypertable parity note (ADR-0016 §2.3) ───────────────

        test("hypertable() marker emits an explanatory note comment, no functional Exposed change") {
            val model =
                ermModel("M") {
                    entity("sensor_readings") {
                        id("id", ErmDataType.Integer(64))
                        attribute("recorded_at", ErmDataType.Timestamp(), nullable = false)
                        hypertable("recorded_at", "7 days")
                    }
                }
            val content = successFiles(model)[0].content
            content shouldContain
                "// Note: entity marked as TimescaleDB hypertable — emitted only in SQL DDL, not in Exposed."
            content shouldNotContain "create_hypertable"
        }

        test("no hypertable() marker produces no hypertable note comment") {
            val model =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                }
            successFiles(model)[0].content shouldNotContain "TimescaleDB hypertable"
        }

        // ── kotlinObjectName() override (ERM Metadata Retrofit) ──────────────

        test("kotlinObjectName() override replaces the mechanically-derived Kotlin object name") {
            val model =
                ermModel("M") {
                    entity("member") {
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
                ermModel("M") {
                    entity("member") { id() }
                }
            val files = successFiles(model)
            files[0].relativePath shouldBe "Member.kt"
            files[0].content shouldContain "public object Member : Table(\"member\")"
        }

        test("kotlinObjectName() override propagates to foreign-key reference() calls on other entities") {
            val model =
                ermModel("M") {
                    val authors =
                        entity("authors") {
                            kotlinObjectName("AuthorsTable")
                            id("id", ErmDataType.Integer(64))
                        }
                    entity("books") {
                        id("id", ErmDataType.Integer(64))
                        foreignKey("author_id", references = authors, nullable = false)
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Books.kt" }.content
            content shouldContain "AuthorsTable.id"
        }

        test("kotlinObjectName() override that is not a valid Kotlin identifier fails the transform") {
            val model =
                ermModel("M") {
                    entity("member") {
                        kotlinObjectName("123-bad")
                        id()
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("kotlinObjectName() override that is a Kotlin hard keyword fails the transform") {
            val model =
                ermModel("M") {
                    entity("member") {
                        kotlinObjectName("object")
                        id()
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("two entities overriding to the same kotlinObjectName collide and fail the transform") {
            val model =
                ermModel("M") {
                    entity("member") {
                        kotlinObjectName("SharedName")
                        id()
                    }
                    entity("account") {
                        kotlinObjectName("SharedName")
                        id()
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("kotlinObjectName() override colliding with an existing enum's Kotlin object name fails the transform") {
            val model =
                ermModel("M") {
                    entity("member") {
                        kotlinObjectName("Status")
                        id()
                    }
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("status", ErmDataType.Enum("Status", listOf("Active", "Inactive")), nullable = false)
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("kotlinObjectName() override with path-traversal characters fails the transform") {
            val model =
                ermModel("M") {
                    entity("member") {
                        kotlinObjectName("../evil")
                        id()
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        // ── Enum columns (ADR-0016 retrofit) ─────────────────────────────────

        test("enum attribute generates a second enum class file and an enumerationByName column") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("status", ErmDataType.Enum("Status", listOf("Active", "Inactive")), nullable = false)
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
        }

        test("two entities referencing the same enum name/values dedupe to a single enum file") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("status", ErmDataType.Enum("Status", listOf("Active", "Inactive")), nullable = false)
                    }
                    entity("accounts") {
                        id("id", ErmDataType.Integer(64))
                        attribute("status", ErmDataType.Enum("Status", listOf("Active", "Inactive")), nullable = false)
                    }
                }
            val files = successFiles(model)
            files.count { it.relativePath == "Status.kt" } shouldBe 1
        }

        test("two ErmDataType.Enum instances with the same name but different values fail the transform") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("status", ErmDataType.Enum("Status", listOf("Active", "Inactive")), nullable = false)
                    }
                    entity("accounts") {
                        id("id", ErmDataType.Integer(64))
                        attribute("status", ErmDataType.Enum("Status", listOf("Open", "Closed")), nullable = false)
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("enum name colliding with an entity's Kotlin object name fails the transform") {
            val model =
                ermModel("M") {
                    entity("status") { id("id", ErmDataType.Integer(64)) }
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("status", ErmDataType.Enum("Status", listOf("Active", "Inactive")), nullable = false)
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("enum literal with spaces is sanitized to a PascalCase constant and uses customEnumeration") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "status",
                            ErmDataType.Enum("Status", listOf("in progress", "done")),
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
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "status",
                            ErmDataType.Enum("Status", listOf("---", "done")),
                            nullable = false,
                        )
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("two enum literals sanitizing to the same Kotlin constant name fail the transform") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "status",
                            ErmDataType.Enum("Status", listOf("In Progress", "In-Progress")),
                            nullable = false,
                        )
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("nullable enum column renders Column<Status?> with .nullable()") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("status", ErmDataType.Enum("Status", listOf("Active", "Inactive")), nullable = true)
                    }
                }
            val content = successFiles(model).first { it.relativePath == "Users.kt" }.content
            content shouldContain "val status: Column<Status?> = enumerationByName<Status>(\"status\", 8).nullable()"
        }

        // ── External enum types (enumType retrofit) ─────────────────────────────

        test("externalFqName enum column imports and references the external type, no enum class file") {
            val model =
                ermModel("M") {
                    entity("members") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "status",
                            ErmDataType.Enum(
                                "MemberStatus",
                                listOf("Active", "Inactive"),
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
                ermModel("M") {
                    entity("members") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "status",
                            ErmDataType.Enum(
                                "MemberStatus",
                                listOf("Active", "Inactive"),
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
                ermModel("M") {
                    entity("members") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "status",
                            ErmDataType.Enum(
                                "MemberStatus",
                                listOf("Active", "Inactive"),
                                externalFqName = "network.lapis.cloud.shared.domain.MemberStatus",
                            ),
                            nullable = false,
                        )
                    }
                    entity("accounts") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "status",
                            ErmDataType.Enum(
                                "MemberStatus",
                                listOf("Active", "Inactive"),
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
                ermModel("M") {
                    entity("members") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "status",
                            ErmDataType.Enum(
                                "MemberStatus",
                                listOf("Active", "Inactive"),
                                externalFqName = "network.lapis.cloud.shared.domain.MemberStatus",
                            ),
                            nullable = false,
                        )
                    }
                    entity("accounts") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "status",
                            ErmDataType.Enum("MemberStatus", listOf("Active", "Inactive")),
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
                ermModel("M") {
                    entity("member_status") { id("id", ErmDataType.Integer(64)) }
                    entity("members") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "status",
                            ErmDataType.Enum(
                                "MemberStatus",
                                listOf("Active", "Inactive"),
                                externalFqName = "network.lapis.cloud.shared.domain.MemberStatus",
                            ),
                            nullable = false,
                        )
                    }
                }
            transform(model).shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>()
        }

        // ── uuidRepresentation option ────────────────────────────────────────

        test("no uuidRepresentation option set renders javaUUID/UUID (unchanged default behaviour)") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Uuid)
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
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Uuid)
                    }
                }
            val content = successFiles(model, mapOf("uuidRepresentation" to "java"))[0].content
            content shouldContain "val id: Column<UUID> = javaUUID(\"id\")"
            content shouldContain "import java.util.UUID"
            content shouldContain "import org.jetbrains.exposed.v1.core.java.javaUUID"
        }

        test("uuidRepresentation = \"kotlin\" renders uuid(...)/Column<Uuid> with kotlin.uuid.Uuid import") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Uuid)
                        attribute("external_ref", ErmDataType.Uuid, nullable = true)
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
                ermModel("M") {
                    val authors = entity("authors") { id("id", ErmDataType.Uuid) }
                    entity("books") {
                        id("id", ErmDataType.Uuid)
                        foreignKey("author_id", references = authors, nullable = false)
                    }
                }
            val files = successFiles(model, mapOf("uuidRepresentation" to "kotlin"))
            val authorsContent = files.first { it.relativePath == "Authors.kt" }.content
            val booksContent = files.first { it.relativePath == "Books.kt" }.content

            authorsContent shouldContain "val id: Column<Uuid> = uuid(\"id\")"
            authorsContent shouldContain "import kotlin.uuid.Uuid"

            booksContent shouldContain "val authorId: Column<Uuid> = reference(\"author_id\", Authors.id)"
            booksContent shouldContain "import kotlin.uuid.Uuid"
            booksContent shouldNotContain "javaUUID"
            booksContent shouldNotContain "java.util.UUID"
        }

        test("uuidRepresentation = \"kotlin\": nullable FK optReference() to a Uuid primary key renders Column<Uuid?>") {
            val model =
                ermModel("M") {
                    val authors = entity("authors") { id("id", ErmDataType.Uuid) }
                    entity("books") {
                        id("id", ErmDataType.Uuid)
                        foreignKey("author_id", references = authors, nullable = true)
                    }
                }
            val booksContent =
                successFiles(model, mapOf("uuidRepresentation" to "kotlin"))
                    .first { it.relativePath == "Books.kt" }
                    .content
            booksContent shouldContain "val authorId: Column<Uuid?> = optReference(\"author_id\", Authors.id)"
        }

        test("unrecognized uuidRepresentation value falls back to the java default rather than failing") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Uuid)
                    }
                }
            val content = successFiles(model, mapOf("uuidRepresentation" to "bogus-typo"))[0].content
            content shouldContain "val id: Column<UUID> = javaUUID(\"id\")"
            content shouldContain "import java.util.UUID"
        }

        // ── dateTimeRepresentation option ────────────────────────────────────

        test("no dateTimeRepresentation option set renders javatime date/datetime (unchanged default behaviour)") {
            val model =
                ermModel("M") {
                    entity("events") {
                        id("id", ErmDataType.Integer(64))
                        attribute("released_on", ErmDataType.Date, nullable = false)
                        attribute("created_at", ErmDataType.Timestamp(), nullable = false)
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
                ermModel("M") {
                    entity("events") {
                        id("id", ErmDataType.Integer(64))
                        attribute("released_on", ErmDataType.Date, nullable = false)
                        attribute("created_at", ErmDataType.Timestamp(), nullable = false)
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
                ermModel("M") {
                    entity("events") {
                        id("id", ErmDataType.Integer(64))
                        attribute("released_on", ErmDataType.Date, nullable = false)
                        attribute("created_at", ErmDataType.Timestamp(), nullable = true)
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

        test("unrecognized dateTimeRepresentation value falls back to the java default rather than failing") {
            val model =
                ermModel("M") {
                    entity("events") {
                        id("id", ErmDataType.Integer(64))
                        attribute("released_on", ErmDataType.Date, nullable = false)
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
                ermModel("M") {
                    entity("events") {
                        id("id", ErmDataType.Uuid)
                        attribute("created_at", ErmDataType.Timestamp(), nullable = false)
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
                ermModel("M") {
                    entity("events") {
                        id("id", ErmDataType.Uuid)
                        attribute("created_at", ErmDataType.Timestamp(), nullable = false)
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
                ermModel("M") {
                    entity("events") {
                        id("id", ErmDataType.Uuid)
                        attribute("created_at", ErmDataType.Timestamp(), nullable = false)
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
