package dev.kuml.codegen.reverse.sql

import dev.kuml.codegen.reverse.ReverseDiagnostic
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.sf.jsqlparser.parser.CCJSqlParserUtil
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

        // ── extractPartialIndexPredicate (V3.4.11 — partial/conditional index support) ──

        test("extractPartialIndexPredicate strips a trivial already-parseable WHERE clause") {
            val (stripped, predicate) =
                SqlStatementCollector.extractPartialIndexPredicate(
                    "CREATE UNIQUE INDEX idx_x ON t (a) WHERE status = 'active'",
                )
            stripped shouldBe "CREATE UNIQUE INDEX idx_x ON t (a)"
            predicate shouldBe "status = 'active'"
        }

        test("extractPartialIndexPredicate strips an IS NULL predicate JSqlParser's grammar cannot parse on its own") {
            // The brief's own motivating example — JSqlParser 5.3's CreateIndex grammar's
            // hand-rolled keyword whitelist excludes IS, so "WHERE consumed_at IS NULL" fails to
            // parse if left attached; this is the core case the whole mechanism exists for.
            val (stripped, predicate) =
                SqlStatementCollector.extractPartialIndexPredicate(
                    "CREATE UNIQUE INDEX idx_x ON t (a) WHERE consumed_at IS NULL",
                )
            stripped shouldBe "CREATE UNIQUE INDEX idx_x ON t (a)"
            predicate shouldBe "consumed_at IS NULL"
            // The stripped statement is now the well-formed, WHERE-less shape JSqlParser already
            // parses fine today — proves the workaround actually unblocks parsing, not just string-splits.
            CCJSqlParserUtil.parse(stripped)
        }

        test("extractPartialIndexPredicate leaves a non-CREATE-INDEX statement unchanged") {
            val (stripped, predicate) =
                SqlStatementCollector.extractPartialIndexPredicate("DELETE FROM t WHERE consumed_at IS NULL")
            stripped shouldBe "DELETE FROM t WHERE consumed_at IS NULL"
            predicate shouldBe null
        }

        test("extractPartialIndexPredicate leaves a non-partial CREATE INDEX unchanged") {
            val (stripped, predicate) =
                SqlStatementCollector.extractPartialIndexPredicate("CREATE INDEX idx_x ON t (a, b)")
            stripped shouldBe "CREATE INDEX idx_x ON t (a, b)"
            predicate shouldBe null
        }

        test("extractPartialIndexPredicate ignores a WHERE-looking token inside a string literal or a paren group") {
            val (stripped, predicate) =
                SqlStatementCollector.extractPartialIndexPredicate(
                    "CREATE INDEX idx_x ON t (lower(name)) WHERE status = 'has WHERE inside'",
                )
            stripped shouldBe "CREATE INDEX idx_x ON t (lower(name))"
            predicate shouldBe "status = 'has WHERE inside'"
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
            val statements = SqlStatementCollector.collect(files = listOf(dir.resolve("dump.sql")), diagnostics = diagnostics)
            val tableNames = statements.map { it.statement }.filterIsInstance<CreateTable>().map { it.table.name }
            tableNames shouldBe listOf("t", "u")
            diagnostics.any { it.code == "REV-SQL-002" } shouldBe true
        }
    })
