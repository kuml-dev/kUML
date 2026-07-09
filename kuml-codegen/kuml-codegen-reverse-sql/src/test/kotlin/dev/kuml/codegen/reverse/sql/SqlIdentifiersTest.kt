package dev.kuml.codegen.reverse.sql

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SqlIdentifiersTest :
    FunSpec({

        test("unquoted identifier is lowercased") {
            SqlIdentifiers.fold("Orders") shouldBe "orders"
            SqlIdentifiers.fold("USER_ID") shouldBe "user_id"
            SqlIdentifiers.fold("orders") shouldBe "orders"
        }

        test("quoted identifier is case-preserved and unquoted") {
            SqlIdentifiers.fold("\"Users\"") shouldBe "Users"
            SqlIdentifiers.fold("\"CamelCol\"") shouldBe "CamelCol"
        }

        test("doubled double-quote inside a quoted identifier is unescaped") {
            SqlIdentifiers.fold("\"a\"\"b\"") shouldBe "a\"b"
        }

        test("whitespace around the token is trimmed") {
            SqlIdentifiers.fold("  orders  ") shouldBe "orders"
        }
    })
