package dev.kuml.codegen.reverse.sql

import dev.kuml.codegen.reverse.ReverseDiagnostic
import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.erm.ErmReverseResult
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

/**
 * Covers inline vs. table-level PK/FK/UNIQUE/CHECK, `ALTER TABLE ADD CONSTRAINT`
 * foreign keys, composite primary keys, and the composite-FK WARN diagnostic
 * (`REV-SQL-011`) — the plan's `ConstraintParsingTest` bullet.
 */
class ConstraintParsingTest :
    FunSpec({

        val engine = PostgresErmReverseEngine()

        fun analyze(sql: String): ErmReverseResult.Success {
            val dir = Files.createTempDirectory("constraint-parsing-")
            Files.writeString(dir.resolve("schema.sql"), sql)
            // ReverseRequest defaults includeGlobs to "**/*.java" — the sql-postgres engine
            // requires the caller to set "**/*.sql" explicitly (plan stolperfalle #9), exactly
            // as the CLI's --format sql branch does.
            val request = ReverseRequest(sourceRoots = listOf(dir), includeGlobs = listOf("**/*.sql"))
            val result = runBlocking { engine.analyze(request) }
            result.shouldBeInstanceOf<ErmReverseResult.Success>()
            return result
        }

        fun ErmModel.entity(name: String) = entities.first { it.name == name }

        test("inline column-level PRIMARY KEY / NOT NULL / UNIQUE") {
            val model =
                analyze(
                    """
                    CREATE TABLE t (
                        id INT PRIMARY KEY,
                        email VARCHAR(255) NOT NULL UNIQUE
                    );
                    """.trimIndent(),
                ).model
            val t = model.entity("t")
            t.attributeByName("id")!!.primaryKey shouldBe true
            t.attributeByName("email")!!.nullable shouldBe false
            t.attributeByName("email")!!.unique shouldBe true
        }

        test("table-level PRIMARY KEY (single column)") {
            val model =
                analyze(
                    """
                    CREATE TABLE t (
                        id INT,
                        PRIMARY KEY (id)
                    );
                    """.trimIndent(),
                ).model
            model.entity("t").attributeByName("id")!!.primaryKey shouldBe true
        }

        test("table-level composite PRIMARY KEY") {
            val model =
                analyze(
                    """
                    CREATE TABLE t (
                        a INT, b INT,
                        PRIMARY KEY (a, b)
                    );
                    """.trimIndent(),
                ).model
            val t = model.entity("t")
            t.attributeByName("a")!!.primaryKey shouldBe true
            t.attributeByName("b")!!.primaryKey shouldBe true
        }

        test("table-level UNIQUE (single column) sets attribute.unique") {
            val model =
                analyze(
                    """
                    CREATE TABLE t (
                        code VARCHAR(10),
                        CONSTRAINT uq_code UNIQUE (code)
                    );
                    """.trimIndent(),
                ).model
            model.entity("t").attributeByName("code")!!.unique shouldBe true
        }

        test("table-level composite UNIQUE becomes an ErmIndex") {
            val model =
                analyze(
                    """
                    CREATE TABLE t (
                        a INT, b INT,
                        CONSTRAINT uq_ab UNIQUE (a, b)
                    );
                    """.trimIndent(),
                ).model
            val t = model.entity("t")
            t.indexes shouldHaveSize 1
            t.indexes[0].unique shouldBe true
            t.indexes[0].attributeIds shouldBe listOf(t.attributeByName("a")!!.id, t.attributeByName("b")!!.id)
        }

        test("inline column-level CHECK") {
            val model =
                analyze(
                    """
                    CREATE TABLE t (
                        price NUMERIC CHECK (price > 0)
                    );
                    """.trimIndent(),
                ).model
            val t = model.entity("t")
            t.checks shouldHaveSize 1
            t.checks[0].expression shouldBe "price > 0"
        }

        test("table-level named CHECK") {
            val model =
                analyze(
                    """
                    CREATE TABLE t (
                        price NUMERIC,
                        CONSTRAINT chk_price CHECK (price >= 0)
                    );
                    """.trimIndent(),
                ).model
            val t = model.entity("t")
            t.checks shouldHaveSize 1
            t.checks[0].name shouldBe "chk_price"
            t.checks[0].expression shouldBe "price >= 0"
        }

        test("inline column-level REFERENCES resolves a foreign key") {
            // NOTE: "parent"/"child" are avoided as table names here — JSqlParser 5.3's
            // grammar treats "parent" as a reserved word in some contexts (e.g. right after
            // REFERENCES) and fails to parse it as a plain identifier there.
            val model =
                analyze(
                    """
                    CREATE TABLE supplier (id INT PRIMARY KEY);
                    CREATE TABLE product (supplier_id INT REFERENCES supplier(id));
                    """.trimIndent(),
                ).model
            val product = model.entity("product")
            val supplier = model.entity("supplier")
            product.attributeByName("supplier_id")!!.foreignKey?.targetEntityId shouldBe supplier.id
        }

        test("table-level named FOREIGN KEY resolves a foreign key") {
            val model =
                analyze(
                    """
                    CREATE TABLE supplier (id INT PRIMARY KEY);
                    CREATE TABLE product (
                        supplier_id INT,
                        CONSTRAINT fk_product_supplier FOREIGN KEY (supplier_id) REFERENCES supplier(id)
                    );
                    """.trimIndent(),
                ).model
            val product = model.entity("product")
            val supplier = model.entity("supplier")
            product.attributeByName("supplier_id")!!.foreignKey?.targetEntityId shouldBe supplier.id
        }

        test("ALTER TABLE ADD CONSTRAINT FOREIGN KEY resolves against a forward-declared target") {
            val model =
                analyze(
                    """
                    CREATE TABLE product (supplier_id INT);
                    CREATE TABLE supplier (id INT PRIMARY KEY);
                    ALTER TABLE product ADD CONSTRAINT fk_product_supplier FOREIGN KEY (supplier_id) REFERENCES supplier(id);
                    """.trimIndent(),
                ).model
            val product = model.entity("product")
            val supplier = model.entity("supplier")
            product.attributeByName("supplier_id")!!.foreignKey?.targetEntityId shouldBe supplier.id
        }

        test("composite foreign key is attached to the first column only, with a WARN diagnostic") {
            val result =
                analyze(
                    """
                    CREATE TABLE supplier (a INT, b INT, PRIMARY KEY (a, b));
                    CREATE TABLE product (
                        x INT, y INT,
                        CONSTRAINT fk_product_supplier FOREIGN KEY (x, y) REFERENCES supplier(a, b)
                    );
                    """.trimIndent(),
                )
            val product = result.model.entity("product")
            product.attributeByName("x")!!.foreignKey shouldNotBe null
            product.attributeByName("y")!!.foreignKey shouldBe null
            result.diagnostics.any { it.code == "REV-SQL-011" && it.severity == ReverseDiagnostic.Severity.WARN } shouldBe true
        }

        test("ALTER TABLE ADD PRIMARY KEY (bare, without CONSTRAINT keyword)") {
            val model =
                analyze(
                    """
                    CREATE TABLE t (id INT);
                    ALTER TABLE t ADD PRIMARY KEY (id);
                    """.trimIndent(),
                ).model
            model.entity("t").attributeByName("id")!!.primaryKey shouldBe true
        }

        test("ALTER TABLE ADD CONSTRAINT UNIQUE (bare column)") {
            val model =
                analyze(
                    """
                    CREATE TABLE t (code VARCHAR(10));
                    ALTER TABLE t ADD CONSTRAINT uq_code UNIQUE (code);
                    """.trimIndent(),
                ).model
            model.entity("t").attributeByName("code")!!.unique shouldBe true
        }

        test("foreign key to an unknown table is skipped with a WARN diagnostic") {
            val result =
                analyze(
                    """
                    CREATE TABLE product (
                        supplier_id INT,
                        CONSTRAINT fk_product_supplier FOREIGN KEY (supplier_id) REFERENCES nonexistent(id)
                    );
                    """.trimIndent(),
                )
            result.model
                .entity("product")
                .attributeByName("supplier_id")!!
                .foreignKey shouldBe null
            result.diagnostics.any { it.code == "REV-SQL-012" } shouldBe true
        }

        test("unparsable statement in an otherwise valid file is skipped, not fatal") {
            val result =
                analyze(
                    """
                    CREATE TABLE t (id INT PRIMARY KEY);
                    THIS IS NOT VALID SQL;
                    CREATE TABLE u (id INT PRIMARY KEY);
                    """.trimIndent(),
                )
            result.model.entities
                .map { it.name }
                .toSet() shouldBe setOf("t", "u")
            result.diagnostics.any { it.code == "REV-SQL-002" } shouldBe true
        }

        test("empty source directory is a Failure with REV-SQL-001") {
            val dir = Files.createTempDirectory("constraint-parsing-empty-")
            val result = runBlocking { engine.analyze(ReverseRequest(sourceRoots = listOf(dir))) }
            result.shouldBeInstanceOf<ErmReverseResult.Failure>()
            result.errors[0].code shouldBe "REV-SQL-001"
        }

        test("engine metadata") {
            engine.id shouldBe "sql-postgres"
            engine.dialect shouldBe "postgres"
            engine.description shouldContain "Postgres"
        }
    })
