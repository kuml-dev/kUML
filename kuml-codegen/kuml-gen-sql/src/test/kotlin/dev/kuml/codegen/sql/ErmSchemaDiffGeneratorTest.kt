package dev.kuml.codegen.sql

import dev.kuml.erm.dsl.ermModel
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmForeignKey
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * ADR-0016 (deferred item) — additive-only ERM schema-diff coverage for
 * [ErmSchemaDiffGenerator] (the diff decision) and [ErmSchemaDiffEmitter] (the
 * rendered SQL for an accepted diff).
 */
class ErmSchemaDiffGeneratorTest :
    FunSpec({

        fun okDiff(
            old: ErmModel,
            new: ErmModel,
        ): ErmSchemaDiff {
            val outcome = ErmSchemaDiffGenerator.diff(old, new)
            return (outcome as? DiffOutcome.Ok)?.diff
                ?: error("expected DiffOutcome.Ok, got $outcome")
        }

        fun refusedReasons(
            old: ErmModel,
            new: ErmModel,
        ): List<String> {
            val outcome = ErmSchemaDiffGenerator.diff(old, new)
            return (outcome as? DiffOutcome.Refused)?.reasons
                ?: error("expected DiffOutcome.Refused, got $outcome")
        }

        fun migrationSql(
            old: ErmModel,
            new: ErmModel,
            dialect: SqlDialect = SqlDialect.POSTGRES,
        ): String {
            val diff = okDiff(old, new)
            return ErmSchemaDiffEmitter(dialect, SqlEmitOptions()).emit(old, new, diff)
        }

        // ── Additive happy path ──────────────────────────────────────────────

        test("new entity added produces CREATE TABLE for it and nothing for unchanged tables") {
            val old =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                }
            val new =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                    entity("orders") { id("id", ErmDataType.Integer(64)) }
                }
            val sql = migrationSql(old, new)
            sql shouldContain "CREATE TABLE orders ("
            sql shouldNotContain "CREATE TABLE users ("
        }

        test("new nullable column on existing entity renders ALTER TABLE ADD COLUMN ... NULL") {
            val old =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                }
            val new =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("nickname", ErmDataType.Varchar(255), nullable = true)
                    }
                }
            val sql = migrationSql(old, new)
            sql shouldContain "ALTER TABLE users ADD COLUMN nickname VARCHAR(255) NULL;"
        }

        test("new NOT NULL column with a default is allowed") {
            val old =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                }
            val new =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("credits", ErmDataType.Integer(32), nullable = false, default = "0")
                    }
                }
            val sql = migrationSql(old, new)
            sql shouldContain "ALTER TABLE users ADD COLUMN credits INTEGER NOT NULL DEFAULT 0;"
        }

        test("new column carrying a foreign key emits both ADD COLUMN and the FK constraint") {
            val old =
                ermModel("M") {
                    entity("teams") { id("id", ErmDataType.Integer(64)) }
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                }
            val new =
                ermModel("M") {
                    val teams = entity("teams") { id("id", ErmDataType.Integer(64)) }
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        foreignKey("team_id", references = teams, nullable = true)
                    }
                }
            val sql = migrationSql(old, new)
            sql shouldContain "ALTER TABLE users ADD COLUMN team_id BIGINT NULL;"
            sql shouldContain "ALTER TABLE users ADD CONSTRAINT fk_users_team_id FOREIGN KEY (team_id) REFERENCES teams(id);"
        }

        test("new index on existing entity renders CREATE INDEX") {
            val old =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("email", ErmDataType.Varchar(255))
                    }
                }
            val new =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("email", ErmDataType.Varchar(255))
                        index("email", unique = true, name = "idx_users_email")
                    }
                }
            val sql = migrationSql(old, new)
            sql shouldContain "CREATE UNIQUE INDEX idx_users_email ON users (email);"
        }

        test("new view renders CREATE VIEW") {
            val old =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                }
            val new =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                    view("all_users", query = "SELECT * FROM users")
                }
            val sql = migrationSql(old, new)
            sql shouldContain "CREATE VIEW all_users AS SELECT * FROM users;"
        }

        test("new CHECK referencing only a newly-added column is allowed") {
            val old =
                ermModel("M") {
                    entity("products") { id("id", ErmDataType.Integer(64)) }
                }
            val new =
                ermModel("M") {
                    entity("products") {
                        id("id", ErmDataType.Integer(64))
                        attribute("price", ErmDataType.Decimal(10, 2), nullable = true)
                        check("price > 0", name = "chk_products_price_positive")
                    }
                }
            val sql = migrationSql(old, new)
            sql shouldContain "ALTER TABLE products ADD CONSTRAINT chk_products_price_positive CHECK (price > 0);"
        }

        test("two identical snapshots produce an empty diff") {
            val model =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                }
            val diff = okDiff(model, model)
            diff.isEmpty shouldBe true
        }

        test("dialect option is threaded through — mysql renders TINYINT(1) for a new boolean column") {
            val old =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                }
            val new =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("active", ErmDataType.Boolean, nullable = false, default = "true")
                    }
                }
            val sql = migrationSql(old, new, dialect = SqlDialect.MYSQL)
            sql shouldContain "active TINYINT(1) NOT NULL DEFAULT true"
        }

        // ── Refusal path ─────────────────────────────────────────────────────

        test("dropped entity refuses and names the entity") {
            val old =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                    entity("legacy") { id("id", ErmDataType.Integer(64)) }
                }
            val new =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                }
            val reasons = refusedReasons(old, new)
            reasons.shouldContain("entity 'legacy' was removed — dropping a table is destructive")
        }

        test("dropped column refuses") {
            val old =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("nickname", ErmDataType.Varchar(255))
                    }
                }
            val new =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                }
            val reasons = refusedReasons(old, new)
            reasons shouldHaveSize 1
            reasons[0] shouldContain "column 'users.nickname' was removed"
        }

        test("column type change refuses") {
            val old =
                ermModel("M") {
                    entity("products") {
                        id("id", ErmDataType.Integer(64))
                        attribute("sku", ErmDataType.Varchar(255))
                    }
                }
            val new =
                ermModel("M") {
                    entity("products") {
                        id("id", ErmDataType.Integer(64))
                        attribute("sku", ErmDataType.Integer(32))
                    }
                }
            val reasons = refusedReasons(old, new)
            reasons[0] shouldContain "column 'products.sku' changed (type)"
        }

        test("column made NOT NULL refuses") {
            val old =
                ermModel("M") {
                    entity("t") {
                        id("id", ErmDataType.Integer(64))
                        attribute("a", ErmDataType.Varchar(255), nullable = true)
                    }
                }
            val new =
                ermModel("M") {
                    entity("t") {
                        id("id", ErmDataType.Integer(64))
                        attribute("a", ErmDataType.Varchar(255), nullable = false)
                    }
                }
            refusedReasons(old, new)[0] shouldContain "made NOT NULL"
        }

        test("column made primary key refuses") {
            val old =
                ermModel("M") {
                    entity("t") {
                        id("id", ErmDataType.Integer(64))
                        attribute("a", ErmDataType.Varchar(255))
                    }
                }
            val new =
                ermModel("M") {
                    entity("t") {
                        id("id", ErmDataType.Integer(64))
                        attribute("a", ErmDataType.Varchar(255), primaryKey = true, nullable = false)
                    }
                }
            refusedReasons(old, new)[0] shouldContain "primary key"
        }

        test("column foreign key changed refuses") {
            val old =
                ermModel("M") {
                    entity("teams") { id("id", ErmDataType.Integer(64)) }
                    entity("t") {
                        id("id", ErmDataType.Integer(64))
                        attribute("team_id", ErmDataType.Integer(64), nullable = true)
                    }
                }
            val new =
                ermModel("M") {
                    val teams = entity("teams") { id("id", ErmDataType.Integer(64)) }
                    entity("t") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "team_id",
                            ErmDataType.Integer(64),
                            nullable = true,
                            foreignKey = ErmForeignKey(targetEntityId = teams),
                        )
                    }
                }
            refusedReasons(old, new)[0] shouldContain "foreign key"
        }

        test("new NOT NULL column without a default refuses as unsafe additive") {
            val old =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                }
            val new =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("required_field", ErmDataType.Varchar(255), nullable = false)
                    }
                }
            val reasons = refusedReasons(old, new)
            reasons[0] shouldContain "'users.required_field' is NOT NULL without a DEFAULT"
        }

        test("rename (old name gone, new name present) refuses via the drop guard rather than being silently emitted") {
            val old =
                ermModel("M") {
                    entity("customer") { id("id", ErmDataType.Integer(64)) }
                }
            val new =
                ermModel("M") {
                    entity("client") { id("id", ErmDataType.Integer(64)) }
                }
            val reasons = refusedReasons(old, new)
            reasons.shouldContain("entity 'customer' was removed — dropping a table is destructive")
            // Not silently treated as additive: the outcome must be a refusal, not an Ok diff
            // that happens to contain a "new" `client` table alongside a dropped `customer`.
            ErmSchemaDiffGenerator.diff(old, new).shouldBeRefused()
        }

        test("removed index refuses; changed index columns refuse") {
            val old =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("email", ErmDataType.Varchar(255))
                        attribute("phone", ErmDataType.Varchar(64))
                        index("email", name = "idx_users_email")
                    }
                }
            val removedIndexNew =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("email", ErmDataType.Varchar(255))
                        attribute("phone", ErmDataType.Varchar(64))
                    }
                }
            refusedReasons(old, removedIndexNew)[0] shouldContain "index 'idx_users_email' on 'users' was removed"

            val changedIndexNew =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("email", ErmDataType.Varchar(255))
                        attribute("phone", ErmDataType.Varchar(64))
                        index("phone", name = "idx_users_email")
                    }
                }
            refusedReasons(old, changedIndexNew)[0] shouldContain "index 'idx_users_email' on 'users' changed"
        }

        test("changed view body (same name) refuses") {
            val old =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                    view("v", query = "SELECT * FROM users")
                }
            val new =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                    view("v", query = "SELECT id FROM users")
                }
            val reasons = refusedReasons(old, new)
            reasons[0] shouldContain "view 'v' body changed"
        }

        test("new CHECK referencing an existing column refuses") {
            val old =
                ermModel("M") {
                    entity("products") {
                        id("id", ErmDataType.Integer(64))
                        attribute("price", ErmDataType.Decimal(10, 2))
                    }
                }
            val new =
                ermModel("M") {
                    entity("products") {
                        id("id", ErmDataType.Integer(64))
                        attribute("price", ErmDataType.Decimal(10, 2))
                        check("price > 0", name = "chk_price_positive")
                    }
                }
            val reasons = refusedReasons(old, new)
            reasons[0] shouldContain "new CHECK on 'products' references existing column(s) (price)"
        }

        test("multiple simultaneous destructive changes are all accumulated, not fail-fast") {
            val old =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("nickname", ErmDataType.Varchar(255))
                    }
                    entity("legacy") { id("id", ErmDataType.Integer(64)) }
                }
            val new =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                }
            val reasons = refusedReasons(old, new)
            reasons shouldHaveSize 2
            reasons.shouldContain("entity 'legacy' was removed — dropping a table is destructive")
            reasons[1] shouldContain "column 'users.nickname' was removed"
        }
    })

private fun DiffOutcome.shouldBeRefused() {
    require(this is DiffOutcome.Refused) { "expected DiffOutcome.Refused, got $this" }
}
