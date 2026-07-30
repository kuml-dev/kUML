package dev.kuml.cli.reverse

import dev.kuml.erm.dsl.ermModel
import dev.kuml.erm.model.ErmDataType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * V3.4.11 — first dedicated test coverage for [ErmModelDslPrinter] (previously untested directly,
 * only exercised transitively via `ReverseCommandSqlCliTest`'s end-to-end CLI assertions). Covers
 * the `index(..., where = "...")` round-trip added alongside partial/conditional index support.
 */
class ErmModelDslPrinterTest :
    FunSpec({

        test("a partial index's where predicate round-trips into the printed index(...) call") {
            val model =
                ermModel(name = "M") {
                    entity(name = "teams") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "consumed_at", type = ErmDataType.Timestamp(), nullable = true)
                        index("consumed_at", unique = true, name = "idx_teams_pending", where = "consumed_at IS NULL")
                    }
                }
            val printed = ErmModelDslPrinter.print(model)
            printed shouldContain
                """index("consumed_at", unique = true, name = "idx_teams_pending", where = "consumed_at IS NULL")"""
        }

        test("a non-partial index prints without a where argument (unchanged default behaviour)") {
            val model =
                ermModel(name = "M") {
                    entity(name = "users") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "email", type = ErmDataType.Varchar(255))
                        index("email", name = "idx_users_email")
                    }
                }
            val printed = ErmModelDslPrinter.print(model)
            printed shouldContain """index("email", name = "idx_users_email")"""
            printed shouldNotContain "where ="
        }

        test("a where predicate containing a double quote is escaped, not left breaking the generated script") {
            val model =
                ermModel(name = "M") {
                    entity(name = "teams") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        attribute(name = "note", type = ErmDataType.Varchar(255), nullable = true)
                        index("note", where = "note = \"x\" AND active")
                    }
                }
            val printed = ErmModelDslPrinter.print(model)
            printed shouldContain "where = \"note = \\\"x\\\" AND active\""
        }
    })
