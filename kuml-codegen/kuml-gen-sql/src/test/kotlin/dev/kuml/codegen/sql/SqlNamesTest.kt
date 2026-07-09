package dev.kuml.codegen.sql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * V3.4.7 — [SqlNames] was narrowed down to just the identifier-safety guard
 * ([SqlNames.requireSafe]); name *derivation* (stereotype-tag lookup,
 * pluralisation, camelCase→snake_case) moved to `UmlToErmTransformer`'s
 * `SqlIdentifiers`, tested there.
 */
class SqlNamesTest :
    FunSpec({

        test("requireSafe accepts a plain identifier") {
            SqlNames.requireSafe("users", "table name", "src") shouldBe "users"
        }

        test("requireSafe accepts underscores and digits (not leading)") {
            SqlNames.requireSafe("auth_users_2", "table name", "src") shouldBe "auth_users_2"
        }

        test("requireSafe rejects a name containing SQL metacharacters") {
            shouldThrow<UnsafeSqlIdentifierException> {
                SqlNames.requireSafe("users; DROP TABLE users; --", "table name", "src")
            }
        }

        test("requireSafe rejects a name with embedded whitespace") {
            shouldThrow<UnsafeSqlIdentifierException> {
                SqlNames.requireSafe("evil name", "column name", "src")
            }
        }

        test("requireSafe rejects a name starting with a digit") {
            shouldThrow<UnsafeSqlIdentifierException> {
                SqlNames.requireSafe("2fast", "table name", "src")
            }
        }

        test("requireSafe rejects a name longer than 63 characters") {
            shouldThrow<UnsafeSqlIdentifierException> {
                SqlNames.requireSafe("a".repeat(64), "table name", "src")
            }
        }

        test("requireSafe accepts exactly 63 characters") {
            val name = "a".repeat(63)
            SqlNames.requireSafe(name, "table name", "src") shouldBe name
        }
    })
