package dev.kuml.codegen.sql

import dev.kuml.codegen.api.CodeGenerationException
import dev.kuml.erm.dsl.ermModel
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmForeignKey
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ReferentialAction
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * V3.4.7 — core DDL-rendering coverage for [ErmSqlEmitter], exercised directly
 * against a typed [ErmModel] (the ERM-first path). [SqlDdlGeneratorTest]
 * covers the UML-direct chain (`UmlToErmTransformer` → [ErmSqlEmitter]) with
 * the same underlying emitter, so behaviour asserted once here does not need
 * to be re-verified from the UML side.
 */
class ErmSqlEmitterTest :
    FunSpec({

        fun emit(
            model: ErmModel,
            dialect: SqlDialect = SqlDialect.POSTGRES,
            withDrop: Boolean = false,
        ): String = ErmSqlEmitter(dialect, SqlEmitOptions(withDrop = withDrop)).emit(model)

        test("single-column PK, NOT NULL/NULL, UNIQUE, DEFAULT render correctly") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(32))
                        attribute("email", ErmDataType.Varchar(255), nullable = false, unique = true)
                        attribute("nickname", ErmDataType.Varchar(255), nullable = true)
                        attribute("credits", ErmDataType.Integer(32), nullable = false, default = "0")
                    }
                }
            val sql = emit(model)
            sql shouldContain "CREATE TABLE users ("
            // Note: the `id()` DSL convenience does not set `autoIncrement` (unlike a real PK
            // synthesized by `UmlToErmTransformer`), so the NOT NULL clause is still rendered here.
            sql shouldContain "id INTEGER NOT NULL PRIMARY KEY"
            sql shouldContain "email VARCHAR(255) NOT NULL UNIQUE"
            sql shouldContain "nickname VARCHAR(255) NULL"
            sql shouldContain "credits INTEGER NOT NULL DEFAULT 0"
        }

        test("composite PK renders as a table-level PRIMARY KEY, not inline") {
            // Built with raw `attribute(...)` (not the `foreignKey()` convenience builder) since
            // the latter never sets `primaryKey = true` — junction tables need both columns to
            // be primary key AND foreign key simultaneously.
            val model =
                ermModel("M") {
                    val students = entity("students") { id("id", ErmDataType.Integer(64)) }
                    val courses = entity("courses") { id("id", ErmDataType.Integer(64)) }
                    entity("students_courses", weak = true) {
                        attribute(
                            "student_id",
                            ErmDataType.Integer(64),
                            primaryKey = true,
                            nullable = false,
                            foreignKey = ErmForeignKey(targetEntityId = students, onDelete = ReferentialAction.CASCADE),
                        )
                        attribute(
                            "course_id",
                            ErmDataType.Integer(64),
                            primaryKey = true,
                            nullable = false,
                            foreignKey = ErmForeignKey(targetEntityId = courses, onDelete = ReferentialAction.CASCADE),
                        )
                    }
                }
            val sql = emit(model)
            sql shouldContain "CREATE TABLE students_courses ("
            sql shouldContain "PRIMARY KEY (student_id, course_id)"
            sql shouldNotContain "student_id INTEGER NOT NULL PRIMARY KEY"
        }

        test("FK column is inline in CREATE TABLE, constraint via ALTER TABLE, ON DELETE/ON UPDATE clauses") {
            val model =
                ermModel("M") {
                    val users = entity("users") { id("id", ErmDataType.Integer(64)) }
                    entity("orders") {
                        id("id", ErmDataType.Integer(64))
                        foreignKey(
                            "user_id",
                            references = users,
                            onDelete = ReferentialAction.CASCADE,
                            nullable = false,
                        )
                    }
                }
            val sql = emit(model)
            sql shouldContain "user_id BIGINT NOT NULL"
            sql shouldContain "-- Foreign Keys"
            sql shouldContain
                "ALTER TABLE orders ADD CONSTRAINT fk_orders_user_id " +
                "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;"
        }

        test("ON DELETE SET NULL and RESTRICT render their clauses; NO_ACTION renders no clause") {
            val model =
                ermModel("M") {
                    val a = entity("a") { id("id", ErmDataType.Integer(64)) }
                    entity("b") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "a_set_null_id",
                            ErmDataType.Integer(64),
                            nullable = true,
                            foreignKey = ErmForeignKey(targetEntityId = a, onDelete = ReferentialAction.SET_NULL),
                        )
                        attribute(
                            "a_restrict_id",
                            ErmDataType.Integer(64),
                            nullable = true,
                            foreignKey = ErmForeignKey(targetEntityId = a, onUpdate = ReferentialAction.RESTRICT),
                        )
                        attribute(
                            "a_no_action_id",
                            ErmDataType.Integer(64),
                            nullable = true,
                            foreignKey = ErmForeignKey(targetEntityId = a),
                        )
                    }
                }
            val sql = emit(model)
            sql shouldContain "FOREIGN KEY (a_set_null_id) REFERENCES a(id) ON DELETE SET NULL;"
            sql shouldContain "FOREIGN KEY (a_restrict_id) REFERENCES a(id) ON UPDATE RESTRICT;"
            sql shouldContain "FOREIGN KEY (a_no_action_id) REFERENCES a(id);"
        }

        test("index renders CREATE INDEX and CREATE UNIQUE INDEX with composite column order preserved") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("last_name", ErmDataType.Varchar(255))
                        attribute("first_name", ErmDataType.Varchar(255))
                        attribute("email", ErmDataType.Varchar(255))
                        index("last_name", "first_name", name = "idx_users_name")
                        index("email", unique = true, name = "idx_users_email_unique")
                    }
                }
            val sql = emit(model)
            sql shouldContain "-- Indexes"
            sql shouldContain "CREATE INDEX idx_users_name ON users (last_name, first_name);"
            sql shouldContain "CREATE UNIQUE INDEX idx_users_email_unique ON users (email);"
        }

        test("index without an explicit name gets a derived default name") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("email", ErmDataType.Varchar(255))
                        index("email")
                    }
                }
            val sql = emit(model)
            sql shouldContain "CREATE INDEX idx_users_email ON users (email);"
        }

        test("view renders CREATE VIEW ... AS ...") {
            val model =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                    view("active_users", query = "SELECT * FROM users WHERE active = true")
                }
            val sql = emit(model)
            sql shouldContain "-- Views"
            sql shouldContain "CREATE VIEW active_users AS SELECT * FROM users WHERE active = true;"
        }

        test("named check constraint renders CONSTRAINT <name> CHECK, anonymous renders bare CHECK") {
            val model =
                ermModel("M") {
                    entity("products") {
                        id("id", ErmDataType.Integer(64))
                        attribute("price", ErmDataType.Decimal(10, 2))
                        check("price > 0", name = "chk_products_price_positive")
                        check("price < 1000000")
                    }
                }
            val sql = emit(model)
            sql shouldContain "CONSTRAINT chk_products_price_positive CHECK (price > 0)"
            sql shouldContain "CHECK (price < 1000000)"
            sql shouldNotContain "CONSTRAINT null"
        }

        test("ErmDataType.Enum attribute auto-derives a CHECK (col IN (...)) constraint (ERM-first path)") {
            // Regression test: an ERM-first model built directly via the `ermModel { }` DSL never
            // goes through `UmlToErmTransformer`, which is the only place that used to add a
            // matching `ErmCheckConstraint` as a side effect. Without emitter-level derivation,
            // this column was silently unconstrained VARCHAR.
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("status", ErmDataType.Enum("Status", listOf("Active", "Inactive")), nullable = false)
                    }
                }
            val sql = emit(model)
            sql shouldContain "status VARCHAR(8) NOT NULL"
            sql shouldContain "CHECK (status IN ('Active', 'Inactive'))"
        }

        test("ErmDataType.Enum attribute does not duplicate an already-present matching CHECK") {
            // Mirrors what `UmlToErmTransformer` already does for a UML-direct enum property —
            // an explicit ErmCheckConstraint with the exact same expression shape should dedupe
            // against the auto-derived one instead of rendering the CHECK twice.
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute("status", ErmDataType.Enum("Status", listOf("Active", "Inactive")), nullable = false)
                        check("status IN ('Active', 'Inactive')")
                    }
                }
            val sql = emit(model)
            val occurrences = Regex(Regex.escape("CHECK (status IN ('Active', 'Inactive'))")).findAll(sql).count()
            occurrences shouldBe 1
        }

        test("ErmDataType.Enum literal single quotes are escaped in the derived CHECK expression") {
            val model =
                ermModel("M") {
                    entity("users") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "status",
                            ErmDataType.Enum("Status", listOf("O'Brien", "Active")),
                            nullable = false,
                        )
                    }
                }
            val sql = emit(model)
            sql shouldContain "CHECK (status IN ('O''Brien', 'Active'))"
        }

        test("topological sort places the FK target entity before the owning entity") {
            // `orders` is declared (and thus assigned `entity_0`) before `users` (`entity_1`) —
            // deliberately the opposite of the FK dependency direction, so this only passes if
            // `ErmSqlEmitter` actually topologically re-sorts rather than preserving declaration
            // order. Uses a raw `attribute(...)` (not the `foreignKey()` convenience builder,
            // which infers the FK column type by looking up the target entity — impossible here
            // since `users`/`entity_1` doesn't exist yet at the point `orders` is declared).
            val model =
                ermModel("M") {
                    entity("orders") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "user_id",
                            ErmDataType.Integer(64),
                            nullable = false,
                            foreignKey = ErmForeignKey(targetEntityId = "entity_1"),
                        )
                    }
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                }
            val sql = emit(model)
            sql.indexOf("CREATE TABLE users (") shouldBeBefore sql.indexOf("CREATE TABLE orders (")
        }

        test("self-referencing FK does not break topological sort") {
            val model =
                ermModel("M") {
                    entity("employees") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "manager_id",
                            ErmDataType.Integer(64),
                            nullable = true,
                            foreignKey = ErmForeignKey(targetEntityId = "entity_0"),
                        )
                    }
                }
            val sql = emit(model)
            sql shouldContain "CREATE TABLE employees ("
            sql shouldContain "FOREIGN KEY (manager_id) REFERENCES employees(id);"
        }

        test("sql-drop=true emits DROP VIEW before DROP TABLE, tables in reverse dependency order") {
            val model =
                ermModel("M") {
                    entity("orders") {
                        id("id", ErmDataType.Integer(64))
                        attribute(
                            "user_id",
                            ErmDataType.Integer(64),
                            nullable = false,
                            foreignKey = ErmForeignKey(targetEntityId = "entity_1"),
                        )
                    }
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                    view("v", query = "SELECT 1")
                }
            val sql = emit(model, withDrop = true)
            val dropView = sql.indexOf("DROP VIEW IF EXISTS v;")
            val dropOrders = sql.indexOf("DROP TABLE IF EXISTS orders;")
            val dropUsers = sql.indexOf("DROP TABLE IF EXISTS users;")
            dropView shouldBeBefore dropOrders
            // reverse dependency order: orders (dependent) dropped before users (dependency)
            dropOrders shouldBeBefore dropUsers
        }

        test("dialect option is honoured — mysql renders AUTO_INCREMENT/TINYINT(1)") {
            val model =
                ermModel("M") {
                    entity("users") {
                        attribute("id", ErmDataType.Integer(64), primaryKey = true, nullable = false, autoIncrement = true)
                        attribute("active", ErmDataType.Boolean, nullable = false)
                    }
                }
            val sql = emit(model, dialect = SqlDialect.MYSQL)
            sql shouldContain "BIGINT AUTO_INCREMENT"
            sql shouldContain "active TINYINT(1) NOT NULL"
        }

        test("unsafe identifier in an ERM-first model refuses to emit DDL") {
            val model =
                ermModel("M") {
                    entity("users; DROP TABLE users; --") {
                        id("id", ErmDataType.Integer(64))
                    }
                }
            shouldThrow<UnsafeSqlIdentifierException> { emit(model) }
        }

        test("a structurally broken ERM model (non-weak entity, no primary key) refuses to emit DDL") {
            val model =
                ermModel("M") {
                    entity("broken") {
                        attribute("name", ErmDataType.Varchar(255))
                    }
                }
            shouldThrow<CodeGenerationException> { emit(model) }
        }

        // ── PostGIS geometry column DDL (ADR-0016 §2.3) ──────────────────────────

        test("a Custom PostGIS geometry column renders the canonical type on POSTGRES") {
            val model =
                ermModel("M") {
                    entity("places") {
                        id("id", ErmDataType.Integer(64))
                        attribute("location", ErmDataType.Custom("geometry(Point,4326)"), nullable = false)
                    }
                }
            val sql = emit(model, dialect = SqlDialect.POSTGRES)
            sql shouldContain "location geometry(Point,4326) NOT NULL"
        }

        test("a Custom PostGIS geometry column is unchanged verbatim on non-Postgres dialects") {
            val model =
                ermModel("M") {
                    entity("places") {
                        id("id", ErmDataType.Integer(64))
                        attribute("location", ErmDataType.Custom("geometry(Point,4326)"), nullable = false)
                    }
                }
            val sql = emit(model, dialect = SqlDialect.SQLITE)
            sql shouldContain "location geometry(Point,4326) NOT NULL"
        }

        // ── TimescaleDB hypertables (ADR-0016 §2.3) ──────────────────────────────

        test("hypertable() marker emits create_hypertable after CREATE TABLE and before FKs/indexes on POSTGRES") {
            val model =
                ermModel("M") {
                    entity("sensor_readings") {
                        id("id", ErmDataType.Integer(64))
                        attribute("recorded_at", ErmDataType.Timestamp(), nullable = false)
                        hypertable("recorded_at", "7 days")
                        index("recorded_at", name = "idx_recorded_at")
                    }
                }
            val sql = emit(model, dialect = SqlDialect.POSTGRES)
            sql shouldContain
                "SELECT create_hypertable('sensor_readings', 'recorded_at', " +
                "if_not_exists => TRUE, chunk_time_interval => INTERVAL '7 days');"
            val createTableEnd = sql.indexOf("SELECT create_hypertable")
            val indexPos = sql.indexOf("CREATE INDEX")
            (createTableEnd > sql.indexOf("CREATE TABLE sensor_readings")) shouldBe true
            (createTableEnd < indexPos) shouldBe true
        }

        test("hypertable() marker without chunkInterval omits the chunk_time_interval argument") {
            val model =
                ermModel("M") {
                    entity("sensor_readings") {
                        id("id", ErmDataType.Integer(64))
                        attribute("recorded_at", ErmDataType.Timestamp(), nullable = false)
                        hypertable("recorded_at")
                    }
                }
            val sql = emit(model, dialect = SqlDialect.POSTGRES)
            sql shouldContain "SELECT create_hypertable('sensor_readings', 'recorded_at', if_not_exists => TRUE);"
        }

        test("hypertable() marker is ignored (no create_hypertable) on non-Postgres dialects") {
            val model =
                ermModel("M") {
                    entity("sensor_readings") {
                        id("id", ErmDataType.Integer(64))
                        attribute("recorded_at", ErmDataType.Timestamp(), nullable = false)
                        hypertable("recorded_at", "7 days")
                    }
                }
            listOf(SqlDialect.MYSQL, SqlDialect.H2, SqlDialect.SQLITE).forEach { dialect ->
                emit(model, dialect = dialect) shouldNotContain "create_hypertable"
            }
        }

        test("no hypertable() marker produces no create_hypertable on any dialect") {
            val model =
                ermModel("M") {
                    entity("sensor_readings") {
                        id("id", ErmDataType.Integer(64))
                        attribute("recorded_at", ErmDataType.Timestamp(), nullable = false)
                    }
                }
            SqlDialect.entries.forEach { dialect -> emit(model, dialect = dialect) shouldNotContain "create_hypertable" }
        }

        test("hypertable() marker with a non-existent timeColumn refuses to emit DDL") {
            val model =
                ermModel("M") {
                    entity("sensor_readings") {
                        id("id", ErmDataType.Integer(64))
                        hypertable("does_not_exist")
                    }
                }
            shouldThrow<CodeGenerationException> { emit(model, dialect = SqlDialect.POSTGRES) }
        }

        test("hypertable() marker with an injection attempt in chunkInterval refuses to emit DDL") {
            val model =
                ermModel("M") {
                    entity("sensor_readings") {
                        id("id", ErmDataType.Integer(64))
                        attribute("recorded_at", ErmDataType.Timestamp(), nullable = false)
                        hypertable("recorded_at", "1 day'); DROP TABLE users;--")
                    }
                }
            shouldThrow<CodeGenerationException> { emit(model, dialect = SqlDialect.POSTGRES) }
        }
    })

private infix fun Int.shouldBeBefore(other: Int) {
    require(this >= 0) { "expected index to be found, was -1" }
    require(other >= 0) { "expected index to be found, was -1" }
    require(this < other) { "expected $this to be before $other" }
}
