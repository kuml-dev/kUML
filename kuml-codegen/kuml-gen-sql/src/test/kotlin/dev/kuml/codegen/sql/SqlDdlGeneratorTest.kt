package dev.kuml.codegen.sql

import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.TagValue
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

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
            val cls =
                UmlClass(
                    id = "u",
                    name = "User",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "email",
                                name = "email",
                                type = UmlTypeRef("String"),
                                multiplicity = Multiplicity(1, 1),
                            ),
                        ),
                )
            val sql = runGenerator(KumlDiagram(name = "D", elements = listOf(cls)))
            sql shouldContain "CREATE TABLE users ("
            sql shouldContain "id BIGSERIAL PRIMARY KEY"
            sql shouldContain "email VARCHAR(255) NOT NULL"
        }

        test("«Transient» attribute is excluded") {
            val cls =
                UmlClass(
                    id = "u",
                    name = "User",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "n",
                                name = "name",
                                type = UmlTypeRef("String"),
                                multiplicity = Multiplicity(1, 1),
                            ),
                            UmlProperty(
                                id = "tmp",
                                name = "temporary",
                                type = UmlTypeRef("String"),
                                multiplicity = Multiplicity(0, 1),
                                appliedStereotypes = listOf(TestStereo(stereotypeName = "Transient")),
                            ),
                        ),
                )
            val sql = runGenerator(KumlDiagram(name = "D", elements = listOf(cls)))
            sql shouldContain "name VARCHAR(255) NOT NULL"
            sql shouldNotContain "temporary"
        }

        test("dialect=mysql uses TINYINT(1) for Boolean and CHAR(36) for UUID") {
            val cls =
                UmlClass(
                    id = "u",
                    name = "User",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "id",
                                name = "id",
                                type = UmlTypeRef("UUID"),
                                multiplicity = Multiplicity(1, 1),
                            ),
                            UmlProperty(
                                id = "act",
                                name = "active",
                                type = UmlTypeRef("Boolean"),
                                multiplicity = Multiplicity(1, 1),
                            ),
                        ),
                )
            val sql =
                runGenerator(
                    KumlDiagram(name = "D", elements = listOf(cls)),
                    mapOf("sql-dialect" to "mysql"),
                )
            sql shouldContain "id CHAR(36) NOT NULL"
            sql shouldContain "active TINYINT(1) NOT NULL"
            sql shouldContain "BIGINT AUTO_INCREMENT"
        }

        test("multiplicity (0,*) writes TODO many-to-many comment") {
            val cls =
                UmlClass(
                    id = "u",
                    name = "User",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "r",
                                name = "roles",
                                type = UmlTypeRef("String"),
                                multiplicity = Multiplicity(0, null),
                            ),
                        ),
                )
            val sql = runGenerator(KumlDiagram(name = "D", elements = listOf(cls)))
            sql shouldContain "-- TODO: many-to-many join table for 'roles'"
        }

        test("«Entity»{tableName} overrides default plural name") {
            val cls =
                UmlClass(
                    id = "u",
                    name = "User",
                    appliedStereotypes =
                        listOf(
                            TestStereo(
                                stereotypeName = "Entity",
                                tags = mapOf("tableName" to TagValue.StringVal("auth_users")),
                            ),
                        ),
                )
            val sql = runGenerator(KumlDiagram(name = "D", elements = listOf(cls)))
            sql shouldContain "CREATE TABLE auth_users ("
        }

        test("«Id» property becomes PRIMARY KEY in place of implicit id") {
            val cls =
                UmlClass(
                    id = "u",
                    name = "User",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "uuid",
                                name = "uuid",
                                type = UmlTypeRef("UUID"),
                                multiplicity = Multiplicity(1, 1),
                                appliedStereotypes = listOf(TestStereo(stereotypeName = "Id")),
                            ),
                        ),
                )
            val sql = runGenerator(KumlDiagram(name = "D", elements = listOf(cls)))
            sql shouldContain "uuid UUID NOT NULL PRIMARY KEY"
            sql shouldNotContain "BIGSERIAL"
        }

        test("association (0,*)↔(1,1) generates FK ALTER TABLE on many-side") {
            val user =
                UmlClass(
                    id = "user-id",
                    name = "User",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "n",
                                name = "name",
                                type = UmlTypeRef("String"),
                                multiplicity = Multiplicity(1, 1),
                            ),
                        ),
                )
            val order =
                UmlClass(
                    id = "order-id",
                    name = "Order",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "t",
                                name = "total",
                                type = UmlTypeRef("BigDecimal"),
                                multiplicity = Multiplicity(1, 1),
                            ),
                        ),
                )
            val assoc =
                UmlAssociation(
                    id = "assoc",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "user-id", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "order-id", multiplicity = Multiplicity(0, null)),
                        ),
                )
            val sql =
                runGenerator(KumlDiagram(name = "D", elements = listOf(user, order, assoc)))
            sql shouldContain "-- Foreign Keys"
            sql shouldContain "ALTER TABLE orders ADD COLUMN user_id BIGINT;"
            sql shouldContain
                "ALTER TABLE orders ADD CONSTRAINT fk_orders_user " +
                "FOREIGN KEY (user_id) REFERENCES users(id);"
        }

        test("Enum class renders as Postgres CREATE TYPE before tables") {
            val en =
                UmlEnumeration(
                    id = "s",
                    name = "Status",
                    literals =
                        listOf(
                            UmlEnumerationLiteral(id = "a", name = "Active"),
                            UmlEnumerationLiteral(id = "i", name = "Inactive"),
                        ),
                )
            val sql = runGenerator(KumlDiagram(name = "D", elements = listOf(en)))
            sql shouldContain "CREATE TYPE status AS ENUM ('Active', 'Inactive');"
        }

        test("sql-drop=true emits DROP TABLE block") {
            val cls = UmlClass(id = "u", name = "User")
            val sql =
                runGenerator(
                    KumlDiagram(name = "D", elements = listOf(cls)),
                    mapOf("sql-drop" to "true"),
                )
            sql shouldContain "DROP TABLE IF EXISTS users;"
        }

        test("default dialect is postgres and writes 'Dialect: postgres' header") {
            val cls = UmlClass(id = "u", name = "User")
            val sql = runGenerator(KumlDiagram(name = "D", elements = listOf(cls)))
            sql shouldContain "Dialect: postgres"
        }

        test("provider is exposed as 'sql' generator id") {
            SqlDdlGeneratorProvider().generator().id shouldBe "sql"
        }
    })
