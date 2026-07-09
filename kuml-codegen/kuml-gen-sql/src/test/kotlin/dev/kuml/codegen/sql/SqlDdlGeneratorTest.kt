package dev.kuml.codegen.sql

import dev.kuml.core.dsl.classDiagram
import dev.kuml.core.model.KumlDiagram
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.association
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.enumOf
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

/**
 * V3.4.7 — [SqlDdlGenerator] now chains every UML diagram through
 * `UmlToErmTransformer` before handing it to [ErmSqlEmitter]. Fixtures below
 * use the DSL (`classDiagram { … }`) rather than hand-built `UmlClass`/
 * `UmlProperty` instances, and `«Entity»`/`«Column»`/`«Id»`/`«Transient»`
 * stereotypes are applied via [ermMappingProfile] — the transformer only
 * recognizes ERM-namespaced stereotype applications (see
 * `AppliedStereotypeTags.ermStereotype`), not arbitrary same-named ones.
 */
class SqlDdlGeneratorTest :
    FunSpec({

        fun runGenerator(
            diagram: KumlDiagram,
            options: Map<String, String> = emptyMap(),
        ): String {
            val out = Files.createTempDirectory("kuml-gen-sql-test").toFile()
            try {
                val files = SqlDdlGenerator().generate(diagram, out, options)
                files.size shouldBe 1
                return files.single().readText()
            } finally {
                out.deleteRecursively()
            }
        }

        test("generates CREATE TABLE for simple class with implicit id PK") {
            val diagram =
                classDiagram("D") {
                    classOf("User") {
                        attribute("email", "String")
                    }
                }
            val sql = runGenerator(diagram)
            sql shouldContain "CREATE TABLE users ("
            sql shouldContain "id BIGSERIAL PRIMARY KEY"
            sql shouldContain "email VARCHAR(255) NOT NULL"
        }

        test("«Transient» attribute is excluded") {
            val diagram =
                classDiagram("D") {
                    applyProfile(ermMappingProfile)
                    classOf("User") {
                        attribute("name", "String")
                        attribute("temporary", "String") {
                            stereotype("Transient")
                        }
                    }
                }
            val sql = runGenerator(diagram)
            sql shouldContain "name VARCHAR(255) NOT NULL"
            sql shouldNotContain "temporary"
        }

        test("dialect=mysql uses TINYINT(1) for Boolean and CHAR(36) for UUID, BIGINT AUTO_INCREMENT for synthetic id") {
            val diagram =
                classDiagram("D") {
                    classOf("User") {
                        attribute("externalId", "UUID")
                        attribute("active", "Boolean")
                    }
                }
            val sql = runGenerator(diagram, mapOf("sql-dialect" to "mysql"))
            sql shouldContain "external_id CHAR(36) NOT NULL"
            sql shouldContain "active TINYINT(1) NOT NULL"
            sql shouldContain "BIGINT AUTO_INCREMENT"
        }

        test("«Entity»{tableName} overrides default plural name") {
            val diagram =
                classDiagram("D") {
                    applyProfile(ermMappingProfile)
                    classOf("User") {
                        stereotype("Entity") {
                            "tableName" to "auth_users"
                        }
                    }
                }
            val sql = runGenerator(diagram)
            sql shouldContain "CREATE TABLE auth_users ("
        }

        test("«Id» property becomes PRIMARY KEY in place of implicit id") {
            val diagram =
                classDiagram("D") {
                    applyProfile(ermMappingProfile)
                    classOf("User") {
                        attribute("uuid", "UUID") {
                            stereotype("Id")
                        }
                    }
                }
            val sql = runGenerator(diagram)
            sql shouldContain "uuid UUID NOT NULL PRIMARY KEY"
            sql shouldNotContain "BIGSERIAL"
        }

        test("association (1)↔(0,*) generates FK inline in CREATE TABLE plus ALTER TABLE ADD CONSTRAINT") {
            val diagram =
                classDiagram("D") {
                    val user = classOf("User") { attribute("name", "String") }
                    val order = classOf("Order") { attribute("total", "BigDecimal") }
                    association(source = user, target = order, id = "assoc") {
                        source { multiplicity("1") }
                        target { multiplicity("0..*") }
                    }
                }
            val sql = runGenerator(diagram)
            sql shouldContain "-- Foreign Keys"
            // V3.4.7: the FK column lives directly in CREATE TABLE (no more ADD COLUMN);
            // only the constraint itself is added afterwards.
            sql shouldContain "user_id BIGINT"
            sql shouldContain
                "ALTER TABLE orders ADD CONSTRAINT fk_orders_user_id " +
                "FOREIGN KEY (user_id) REFERENCES users(id);"
        }

        test("many-to-many association generates a real junction table with a composite PK") {
            val diagram =
                classDiagram("D") {
                    val student = classOf("Student") { attribute("name", "String") }
                    val course = classOf("Course") { attribute("title", "String") }
                    association(source = student, target = course) {
                        source { multiplicity("0..*") }
                        target { multiplicity("0..*") }
                    }
                }
            val sql = runGenerator(diagram)
            sql shouldContain "CREATE TABLE students_courses ("
            sql shouldContain "PRIMARY KEY (student_id, course_id)"
            sql shouldNotContain "TODO"
        }

        test("Enum attribute renders as VARCHAR + CHECK constraint (no more Postgres CREATE TYPE)") {
            val diagram =
                classDiagram("D") {
                    val status =
                        enumOf("Status") {
                            literal("Active")
                            literal("Inactive")
                        }
                    classOf("User") {
                        attribute("status", status)
                    }
                }
            val sql = runGenerator(diagram)
            sql shouldNotContain "CREATE TYPE"
            sql shouldContain "status VARCHAR(8) NOT NULL"
            sql shouldContain "CHECK (status IN ('Active', 'Inactive'))"
        }

        test("sql-drop=true emits DROP TABLE block") {
            val diagram = classDiagram("D") { classOf("User") }
            val sql = runGenerator(diagram, mapOf("sql-drop" to "true"))
            sql shouldContain "DROP TABLE IF EXISTS users;"
        }

        test("default dialect is postgres and writes 'Dialect: postgres' header") {
            val diagram = classDiagram("D") { classOf("User") }
            val sql = runGenerator(diagram)
            sql shouldContain "Dialect: postgres"
        }

        test("provider is exposed as 'sql' generator id") {
            SqlDdlGeneratorProvider().generator().id shouldBe "sql"
        }
    })
