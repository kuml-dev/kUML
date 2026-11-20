package dev.kuml.cli.workspace

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.KumlCli
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files

class WorkspaceFixAliasesCommandTest :
    FunSpec({
        // An alias type is a diagram type, so the document needs a kuml block to avoid OKF-E-003.
        val body = "body\n```kuml\nx\n```\n"
        val alias = "---\ntype: Sysml2Bdd\n---\n$body"
        val fixed = "---\ntype: Sysml2BlockDefinition\n---\n$body"

        fun withRoot(block: (File) -> Unit) {
            val root = Files.createTempDirectory("kuml-fix-aliases").toFile()
            try {
                block(root)
            } finally {
                root.deleteRecursively()
            }
        }

        fun cli(vararg args: String) = KumlCli().test(listOf("workspace", "validate") + args.toList())

        test("plain validate never writes") {
            withRoot { root ->
                val f = File(root, "a.md").apply { writeText(alias) }
                cli(root.absolutePath)
                f.readText() shouldBe alias
            }
        }

        test("--fix-aliases rewrites alias files only") {
            withRoot { root ->
                val a = File(root, "a.md").apply { writeText(alias) }
                val c = File(root, "c.md").apply { writeText("---\ntype: Concept\n---\nx\n") }
                val u = File(root, "u.md").apply { writeText("---\ntype: Mystery\n---\nx\n") }
                val r = cli(root.absolutePath, "--fix-aliases")
                r.statusCode shouldBe 0
                a.readText() shouldBe fixed
                c.readText() shouldBe "---\ntype: Concept\n---\nx\n"
                u.readText() shouldBe "---\ntype: Mystery\n---\nx\n"
                r.output shouldContain "fixed      a.md: Sysml2Bdd -> Sysml2BlockDefinition"
                r.output shouldContain "Rewrote 1 file(s), skipped 0."
                cli(root.absolutePath, "--fix-aliases").output shouldContain "No alias type ids to rewrite."
            }
        }

        test("--fix-aliases --dry-run writes nothing") {
            withRoot { root ->
                val a = File(root, "a.md").apply { writeText(alias) }
                val r = cli(root.absolutePath, "--fix-aliases", "--dry-run")
                r.statusCode shouldBe 0
                a.readText() shouldBe alias
                r.output shouldContain "would fix  a.md: Sysml2Bdd -> Sysml2BlockDefinition"
                r.output shouldContain "Dry run: would rewrite 1 file(s), skipped 0; nothing written."
            }
        }

        test("--dry-run alone is a usage error") {
            withRoot { root ->
                val a = File(root, "a.md").apply { writeText(alias) }
                val r = cli(root.absolutePath, "--dry-run")
                r.statusCode shouldBe 2
                a.readText() shouldBe alias
            }
        }

        test("validation errors keep exit 5 but the fix is still applied") {
            withRoot { root ->
                val a = File(root, "a.md").apply { writeText("---\ntype: Sysml2Bdd\n---\n[x](missing.md)\n") }
                val r = cli(root.absolutePath, "--fix-aliases")
                r.statusCode shouldBe 5
                a.readText() shouldContain "type: Sysml2BlockDefinition"
            }
        }

        test("a symlinked alias file is skipped with exit 4") {
            withRoot { root ->
                val outsideDir = Files.createTempDirectory("kuml-fix-outside").toFile()
                try {
                    val outside = File(outsideDir, "o.md").apply { writeText(alias) }
                    if (runCatching { Files.createSymbolicLink(File(root, "l.md").toPath(), outside.toPath()) }.isFailure) return@withRoot
                    val r = cli(root.absolutePath, "--fix-aliases")
                    r.statusCode shouldBe 4
                    r.stderr shouldContain "skipped    l.md"
                    outside.readText() shouldBe alias
                } finally {
                    outsideDir.deleteRecursively()
                }
            }
        }

        test("-o json carries fixes, and no fixes key without the flag") {
            withRoot { root ->
                File(root, "a.md").apply { writeText(alias) }
                val r = cli(root.absolutePath, "--fix-aliases", "-o", "json")
                val obj = Json.parseToJsonElement(r.stdout).jsonObject
                val fix =
                    obj
                        .getValue("fixes")
                        .jsonArray
                        .single()
                        .jsonObject
                fix.getValue("status").jsonPrimitive.content shouldBe "rewritten"
                fix.getValue("alias").jsonPrimitive.content shouldBe "Sysml2Bdd"
                fix.getValue("canonical").jsonPrimitive.content shouldBe "Sysml2BlockDefinition"

                File(root, "b.md").writeText(alias)
                val plain = Json.parseToJsonElement(cli(root.absolutePath, "-o", "json").stdout).jsonObject
                plain.containsKey("fixes") shouldBe false
                cli(root.absolutePath, "-o", "json").stdout shouldNotContain "\"fixes\""
            }
        }

        test("--document limits the rewrite to the selected document") {
            withRoot { root ->
                val a = File(root, "a.md").apply { writeText(alias) }
                val b = File(root, "b.md").apply { writeText(alias) }
                val r = cli(root.absolutePath, "--document", "a.md", "--fix-aliases")
                r.statusCode shouldBe 0
                a.readText() shouldBe fixed
                b.readText() shouldBe alias
            }
        }
    })
