package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * ADR-0016 (deferred item) — CLI smoke tests for `kuml generate --sql-migration`,
 * the additive-only ERM schema-diff Flyway migration mode wired into
 * [GenerateCommand] alongside the classic single-script `generate` path.
 */
class GenerateCommandSqlMigrationTest :
    FunSpec({

        val oldFixture = File("src/test/resources/erm/migration-v1-users.kuml.kts")
        val additiveNewFixture = File("src/test/resources/erm/migration-v2-users-nickname.kuml.kts")
        val destructiveNewFixture = File("src/test/resources/erm/migration-v2-drop-users.kuml.kts")
        val nonErmFixture = File("src/test/resources/minimal.kuml.kts")

        fun tempOutDir(): File = Files.createTempDirectory("kuml-generate-sql-migration-test").toFile()

        test("additive delta produces V<version>__<description>.sql and exits 0") {
            val out = tempOutDir()
            try {
                val result =
                    KumlCli().test(
                        listOf(
                            "generate",
                            "--sql-migration",
                            "--from",
                            oldFixture.absolutePath,
                            "--to",
                            additiveNewFixture.absolutePath,
                            "--version",
                            "2",
                            "--description",
                            "add_nickname",
                            "-o",
                            out.absolutePath,
                        ),
                    )
                result.statusCode shouldBe 0
                result.output shouldContain "Generated:"
                result.output shouldContain "V2__add_nickname.sql"
                val migrationFile = File(out, "V2__add_nickname.sql")
                migrationFile.exists() shouldBe true
                migrationFile.readText() shouldContain "ALTER TABLE users ADD COLUMN nickname VARCHAR(255) NULL;"
            } finally {
                out.deleteRecursively()
            }
        }

        test("destructive delta refuses, exits SCRIPT_ERROR, carries the refusal reason, writes no file") {
            val out = tempOutDir()
            try {
                val result =
                    KumlCli().test(
                        listOf(
                            "generate",
                            "--sql-migration",
                            "--from",
                            oldFixture.absolutePath,
                            "--to",
                            destructiveNewFixture.absolutePath,
                            "--version",
                            "2",
                            "--description",
                            "drop_users",
                            "-o",
                            out.absolutePath,
                        ),
                    )
                result.statusCode shouldBe ExitCodes.SCRIPT_ERROR
                result.output shouldContain "entity 'users' was removed"
                out.listFiles().orEmpty().toList() shouldBe emptyList()
            } finally {
                out.deleteRecursively()
            }
        }

        test("missing --to is a usage error") {
            val out = tempOutDir()
            try {
                val result =
                    KumlCli().test(
                        listOf(
                            "generate",
                            "--sql-migration",
                            "--from",
                            oldFixture.absolutePath,
                            "--version",
                            "2",
                            "--description",
                            "add_nickname",
                            "-o",
                            out.absolutePath,
                        ),
                    )
                result.statusCode shouldBe ExitCodes.USAGE
                result.output shouldContain "--to"
            } finally {
                out.deleteRecursively()
            }
        }

        test("mixing -i with --sql-migration is a usage error") {
            val out = tempOutDir()
            try {
                val result =
                    KumlCli().test(
                        listOf(
                            "generate",
                            "--sql-migration",
                            "-i",
                            oldFixture.absolutePath,
                            "--from",
                            oldFixture.absolutePath,
                            "--to",
                            additiveNewFixture.absolutePath,
                            "--version",
                            "2",
                            "--description",
                            "add_nickname",
                            "-o",
                            out.absolutePath,
                        ),
                    )
                result.statusCode shouldBe ExitCodes.USAGE
                result.output shouldContain "mutually exclusive"
            } finally {
                out.deleteRecursively()
            }
        }

        test("a --from/--to script that isn't an ERM script fails with a clear error") {
            val out = tempOutDir()
            try {
                val result =
                    KumlCli().test(
                        listOf(
                            "generate",
                            "--sql-migration",
                            "--from",
                            nonErmFixture.absolutePath,
                            "--to",
                            additiveNewFixture.absolutePath,
                            "--version",
                            "2",
                            "--description",
                            "add_nickname",
                            "-o",
                            out.absolutePath,
                        ),
                    )
                result.statusCode shouldBe ExitCodes.SCRIPT_ERROR
                result.output shouldContain "ERM script"
            } finally {
                out.deleteRecursively()
            }
        }

        test("classic mode without -i is a usage error (regression: -i is no longer unconditionally required)") {
            val out = tempOutDir()
            try {
                val result = KumlCli().test(listOf("generate", "-o", out.absolutePath))
                result.statusCode shouldBe ExitCodes.USAGE
                result.output shouldContain "--input"
            } finally {
                out.deleteRecursively()
            }
        }
    })
