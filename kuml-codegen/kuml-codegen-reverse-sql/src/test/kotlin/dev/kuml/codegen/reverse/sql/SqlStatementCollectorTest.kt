package dev.kuml.codegen.reverse.sql

import dev.kuml.codegen.reverse.ReverseDiagnostic
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.sf.jsqlparser.statement.create.table.CreateTable
import java.nio.file.Files

/**
 * Covers [SqlStatementCollector]'s dollar-quote-aware fallback splitter: a
 * `DO $$ ... $$;` anonymous PL/pgSQL block (unsupported by JSqlParser's
 * grammar, common in `pg_dump`/migration output) must not prevent the
 * surrounding `CREATE TABLE` statements from being recovered.
 */
class SqlStatementCollectorTest :
    FunSpec({

        test("splitStatements keeps a semicolon inside a dollar-quoted body intact") {
            val sql =
                """
                CREATE TABLE t (id INT);
                CREATE FUNCTION f() RETURNS void AS $$
                BEGIN
                    INSERT INTO t (id) VALUES (1);
                    INSERT INTO t (id) VALUES (2);
                END;
                $$ LANGUAGE plpgsql;
                CREATE TABLE u (id INT);
                """.trimIndent()
            val parts = SqlStatementCollector.splitStatements(sql)
            parts shouldHaveSize 3
            parts[0].trim() shouldBe "CREATE TABLE t (id INT)"
            parts[1] shouldContain "\$\$"
            parts[2].trim() shouldBe "CREATE TABLE u (id INT)"
        }

        test("splitStatements keeps a semicolon inside a string literal intact") {
            val sql = "CREATE TABLE t (note TEXT DEFAULT 'a;b');\nCREATE TABLE u (id INT);"
            val parts = SqlStatementCollector.splitStatements(sql)
            parts shouldHaveSize 2
            parts[0] shouldContain "'a;b'"
        }

        test("collect recovers CREATE TABLE statements around an unsupported DO block") {
            val dir = Files.createTempDirectory("sql-collector-")
            Files.writeString(
                dir.resolve("dump.sql"),
                """
                CREATE TABLE t (id INT PRIMARY KEY);
                DO $$
                BEGIN
                    UPDATE t SET id = 1;
                    UPDATE t SET id = 2;
                END
                $$;
                CREATE TABLE u (id INT PRIMARY KEY);
                """.trimIndent(),
            )
            val diagnostics = mutableListOf<ReverseDiagnostic>()
            val statements = SqlStatementCollector.collect(listOf(dir.resolve("dump.sql")), diagnostics)
            val tableNames = statements.map { it.first }.filterIsInstance<CreateTable>().map { it.table.name }
            tableNames shouldBe listOf("t", "u")
            diagnostics.any { it.code == "REV-SQL-002" } shouldBe true
        }
    })
