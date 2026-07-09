package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import java.nio.file.Files

/**
 * V3.4.9 — CLI smoke tests for `kuml reverse --format sql`: SQL DDL → ERM
 * reverse, `-o` file output, `--list-engines`, error paths, and a round-trip
 * through `kuml render` to prove the generated `*.kuml.kts` is valid kUML DSL.
 */
class ReverseCommandSqlCliTest :
    FunSpec({

        val ecommerceSql =
            """
            CREATE TABLE customers (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                email VARCHAR(255) NOT NULL UNIQUE
            );

            CREATE TABLE orders (
                id SERIAL PRIMARY KEY,
                customer_id UUID NOT NULL,
                total NUMERIC(10,2) NOT NULL,
                CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
            );
            """.trimIndent()

        test("--format sql on a single file emits ermModel DSL on stdout") {
            val file = Files.createTempFile("kuml-reverse-sql-", ".sql")
            Files.writeString(file, ecommerceSql)

            val result = KumlCli().test(listOf("reverse", file.toString(), "--format", "sql"))
            result.statusCode shouldBe 0
            result.stdout shouldContain "ermModel("
            result.stdout shouldContain """entity("customers")"""
            result.stdout shouldContain """entity("orders")"""
            result.stdout shouldContain "'sql-postgres' engine"
        }

        test("--format sql on a directory picks up all *.sql files") {
            val dir = Files.createTempDirectory("kuml-reverse-sql-dir-")
            Files.writeString(dir.resolve("schema.sql"), ecommerceSql)

            val result = KumlCli().test(listOf("reverse", dir.toString(), "--format", "sql"))
            result.statusCode shouldBe 0
            result.stdout shouldContain "ermModel("
            result.stdout shouldContain """entity("customers")"""
        }

        test("--output writes the ERM DSL to a file") {
            val file = Files.createTempFile("kuml-reverse-sql-out-", ".sql")
            Files.writeString(file, ecommerceSql)
            val outFile = Files.createTempFile("kuml-reverse-sql-out-", ".kuml.kts").toFile()

            val result = KumlCli().test(listOf("reverse", file.toString(), "--format", "sql", "--output", outFile.absolutePath))
            result.statusCode shouldBe 0
            outFile.exists() shouldBe true
            val text = outFile.readText()
            text shouldContain "ermModel("
            text shouldContain """entity("customers")"""
        }

        test("--model-name is reflected in the ermModel name") {
            val file = Files.createTempFile("kuml-reverse-sql-name-", ".sql")
            Files.writeString(file, ecommerceSql)

            val result = KumlCli().test(listOf("reverse", file.toString(), "--format", "sql", "--model-name", "ShopDb"))
            result.statusCode shouldBe 0
            result.stdout shouldContain """ermModel(name = "ShopDb")"""
        }

        test("empty source directory exits with REVERSE_SQL_PARSE_FAILED") {
            val dir = Files.createTempDirectory("kuml-reverse-sql-empty-")
            val result = KumlCli().test(listOf("reverse", dir.toString(), "--format", "sql"))
            result.statusCode shouldBe ExitCodes.REVERSE_SQL_PARSE_FAILED
            result.stderr shouldContain "No .sql source files found"
        }

        test("unknown --dialect exits with REVERSE_ENGINE_NOT_FOUND") {
            val file = Files.createTempFile("kuml-reverse-sql-dialect-", ".sql")
            Files.writeString(file, ecommerceSql)

            val result = KumlCli().test(listOf("reverse", file.toString(), "--format", "sql", "--dialect", "mysql"))
            result.statusCode shouldBe ExitCodes.REVERSE_ENGINE_NOT_FOUND
            result.stderr shouldContain "Unknown SQL dialect 'mysql'"
        }

        test("missing <source-dir> for --format sql exits with SCRIPT_ERROR") {
            val result = KumlCli().test(listOf("reverse", "--format", "sql"))
            result.statusCode shouldBe ExitCodes.SCRIPT_ERROR
            result.stderr shouldContain "Missing argument"
        }

        test("nonexistent source path for --format sql exits with IO_ERROR") {
            val result = KumlCli().test(listOf("reverse", "/no/such/path.sql", "--format", "sql"))
            result.statusCode shouldBe ExitCodes.IO_ERROR
        }

        test("--list-engines includes the sql-postgres engine") {
            val result = KumlCli().test("reverse --list-engines")
            result.statusCode shouldBe 0
            result.stdout shouldContain "sql-postgres"
            result.stdout shouldContain "--format sql --dialect postgres"
        }

        test("diagnostic summary is printed to stderr for a lossy column type") {
            val file = Files.createTempFile("kuml-reverse-sql-diag-", ".sql")
            Files.writeString(file, "CREATE TABLE t (id INT PRIMARY KEY, tag TSVECTOR);")

            val result = KumlCli().test(listOf("reverse", file.toString(), "--format", "sql"))
            result.statusCode shouldBe 0
            result.stderr.shouldNotBeEmpty()
        }

        test("generated DSL round-trips through kuml render as a valid ERM SVG") {
            val file = Files.createTempFile("kuml-reverse-sql-render-", ".sql")
            Files.writeString(file, ecommerceSql)
            val dslFile = Files.createTempFile("kuml-reverse-sql-render-", ".kuml.kts").toFile()

            val reverseResult =
                KumlCli().test(listOf("reverse", file.toString(), "--format", "sql", "--output", dslFile.absolutePath))
            reverseResult.statusCode shouldBe 0

            val svgFile = Files.createTempFile("kuml-reverse-sql-render-", ".svg").toFile()
            val renderResult =
                KumlCli().test(listOf("render", dslFile.absolutePath, "--format", "svg", "--output", svgFile.absolutePath))
            renderResult.statusCode shouldBe 0
            val svg = svgFile.readText()
            svg shouldContain "<svg"
            svg shouldContain "customers"
            svg shouldContain "orders"
        }
    })
