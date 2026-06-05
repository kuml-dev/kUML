package dev.kuml.codegen.sql

import dev.kuml.uml.TagValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SqlNamesTest :
    FunSpec({

        test("tableName uses «Entity»{tableName=…} when present") {
            val name =
                SqlNames.tableName(
                    "User",
                    listOf(
                        TestStereo(
                            stereotypeName = "Entity",
                            tags = mapOf("tableName" to TagValue.StringVal("auth_users")),
                        ),
                    ),
                )
            name shouldBe "auth_users"
        }

        test("tableName falls back to lowercase + pluralization") {
            SqlNames.tableName("User", emptyList()) shouldBe "users"
            SqlNames.tableName("Person", emptyList()) shouldBe "persons"
            SqlNames.tableName("Story", emptyList()) shouldBe "stories"
            SqlNames.tableName("Class", emptyList()) shouldBe "classes"
        }

        test("columnName uses «Column»{name=…} when present") {
            val name =
                SqlNames.columnName(
                    "emailAddress",
                    listOf(
                        TestStereo(
                            stereotypeName = "Column",
                            tags = mapOf("name" to TagValue.StringVal("email")),
                        ),
                    ),
                )
            name shouldBe "email"
        }

        test("columnName falls back to camelCase → snake_case") {
            SqlNames.columnName("emailAddress", emptyList()) shouldBe "email_address"
            SqlNames.columnName("id", emptyList()) shouldBe "id"
            SqlNames.columnName("createdAt", emptyList()) shouldBe "created_at"
        }
    })
