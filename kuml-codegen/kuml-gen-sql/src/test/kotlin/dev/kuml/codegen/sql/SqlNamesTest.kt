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
            SqlNames.requireSafe(name = "users", what = "table name", source = "src") shouldBe "users"
        }

        test("requireSafe accepts underscores and digits (not leading)") {
            SqlNames.requireSafe(name = "auth_users_2", what = "table name", source = "src") shouldBe "auth_users_2"
        }

        test("requireSafe rejects a name containing SQL metacharacters") {
            shouldThrow<UnsafeSqlIdentifierException> {
                SqlNames.requireSafe(name = "users; DROP TABLE users; --", what = "table name", source = "src")
            }
        }

        test("requireSafe rejects a name with embedded whitespace") {
            shouldThrow<UnsafeSqlIdentifierException> {
                SqlNames.requireSafe(name = "evil name", what = "column name", source = "src")
            }
        }

        test("requireSafe rejects a name starting with a digit") {
            shouldThrow<UnsafeSqlIdentifierException> {
                SqlNames.requireSafe(name = "2fast", what = "table name", source = "src")
            }
        }

        test("requireSafe rejects a name longer than 63 characters") {
            shouldThrow<UnsafeSqlIdentifierException> {
                SqlNames.requireSafe(name = "a".repeat(64), what = "table name", source = "src")
            }
        }

        test("requireSafe accepts exactly 63 characters") {
            val name = "a".repeat(63)
            SqlNames.requireSafe(name = name, what = "table name", source = "src") shouldBe name
        }
    })
