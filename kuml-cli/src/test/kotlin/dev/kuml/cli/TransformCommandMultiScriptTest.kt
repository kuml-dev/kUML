package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

class TransformCommandMultiScriptTest :
    FunSpec({

        val root = File("src/test/resources/multi-script")

        fun script(path: String) = File(root, path).absolutePath

        fun tempOut(): File = Files.createTempDirectory("kuml-multi-script-test").toFile()

        fun File.contents(): Map<String, String> =
            walkTopDown().filter { it.isFile }.associate { it.relativeTo(this).invariantSeparatorsPath to it.readText() }

        val split = listOf("split/00-members.kuml.kts", "split/10-orders.kuml.kts", "split/20-tags.kuml.kts")

        test("single-script output stays byte-identical to the pre-change golden output") {
            listOf("uml-to-exposed-via-erm" to "exposed", "uml-to-jpa" to "jpa").forEach { (transformer, golden) ->
                val out = tempOut()
                try {
                    val result =
                        KumlCli().test(
                            listOf(
                                "transform",
                                script("single/model.kuml.kts"),
                                "-t",
                                transformer,
                                "--package",
                                if (golden == "jpa") "demo.jpa" else "demo.db",
                                "-o",
                                out.absolutePath,
                            ),
                        )
                    result.statusCode shouldBe 0
                    out.contents() shouldBe File(root, "golden/$golden").contents()
                } finally {
                    out.deleteRecursively()
                }
            }
        }

        test("split scripts produce exactly the output of the equivalent single script") {
            val outSplit = tempOut()
            val outSingle = tempOut()
            try {
                val common = listOf("-t", "uml-to-exposed-via-erm", "--options", "idType=uuid", "--package", "demo.db")
                val r1 = KumlCli().test(listOf("transform") + split.map(::script) + common + listOf("-o", outSplit.absolutePath))
                r1.statusCode shouldBe 0
                r1.output shouldContain "Merged 3 script(s) into one model; generated 4 file(s)"
                val r2 =
                    KumlCli().test(
                        listOf("transform", script("single/model.kuml.kts")) + common + listOf("-o", outSingle.absolutePath),
                    )
                r2.statusCode shouldBe 0
                outSplit.contents() shouldBe outSingle.contents()
                outSplit.contents().keys.sorted() shouldBe
                    listOf("CustomerOrderTable.kt", "MemberTable.kt", "OrdersTags.kt", "TagTable.kt")
            } finally {
                outSplit.deleteRecursively()
                outSingle.deleteRecursively()
            }
        }

        test("argument order does not change the output") {
            val a = tempOut()
            val b = tempOut()
            try {
                val common = listOf("-t", "uml-to-exposed-via-erm", "--package", "demo.db")
                KumlCli().test(listOf("transform") + split.map(::script) + common + listOf("-o", a.absolutePath)).statusCode shouldBe 0
                KumlCli()
                    .test(
                        listOf("transform") + listOf(split[2], split[0], split[1]).map(::script) + common + listOf("-o", b.absolutePath),
                    ).statusCode shouldBe 0
                a.contents() shouldBe b.contents()
            } finally {
                a.deleteRecursively()
                b.deleteRecursively()
            }
        }

        test("a merge conflict exits with MODEL_MERGE_CONFLICT, names table, class and attribute and writes nothing") {
            val out = tempOut()
            try {
                val result =
                    KumlCli().test(
                        listOf(
                            "transform",
                            script("conflict/a.kuml.kts"),
                            script("conflict/b.kuml.kts"),
                            "-t",
                            "uml-to-exposed-via-erm",
                            "-o",
                            out.absolutePath,
                        ),
                    )
                result.statusCode shouldBe ExitCodes.MODEL_MERGE_CONFLICT
                result.stderr shouldContain "member"
                result.stderr shouldContain "Member"
                result.stderr shouldContain "nickname"
                out.contents() shouldBe emptyMap()
            } finally {
                out.deleteRecursively()
            }
        }

        test("a non-class script in the mix is a script error naming the file") {
            val out = tempOut()
            try {
                val result =
                    KumlCli().test(
                        listOf(
                            "transform",
                            script("split/00-members.kuml.kts"),
                            script("mixed/c4.kuml.kts"),
                            "-t",
                            "uml-to-exposed-via-erm",
                            "-o",
                            out.absolutePath,
                        ),
                    )
                result.statusCode shouldBe ExitCodes.SCRIPT_ERROR
                result.stderr shouldContain "c4.kuml.kts"
                result.stderr shouldContain "UML class diagrams only"
            } finally {
                out.deleteRecursively()
            }
        }

        test("--from bpmn with several scripts is a usage error") {
            val result =
                KumlCli().test(
                    listOf(
                        "transform",
                        script("split/00-members.kuml.kts"),
                        script("split/10-orders.kuml.kts"),
                        "--from",
                        "bpmn",
                        "--to",
                        "uml-activity",
                        "-o",
                        "x",
                    ),
                )
            result.statusCode shouldBe ExitCodes.USAGE
        }

        test("the same file twice is a usage error") {
            val f = script("split/00-members.kuml.kts")
            val result = KumlCli().test(listOf("transform", f, f, "-t", "uml-to-exposed-via-erm", "-o", "x"))
            result.statusCode shouldBe ExitCodes.USAGE
        }

        test("--package conflicting with --options package= is a usage error") {
            val result =
                KumlCli().test(
                    listOf(
                        "transform",
                        script("single/model.kuml.kts"),
                        "-t",
                        "uml-to-exposed-via-erm",
                        "--package",
                        "a.b",
                        "--options",
                        "package=c.d",
                        "-o",
                        "x",
                    ),
                )
            result.statusCode shouldBe ExitCodes.USAGE
        }

        test("--help mentions one or more scripts") {
            KumlCli().test(listOf("transform", "--help")).output shouldContain "one or more"
        }

        test("a transformer that does not consume UML class diagrams is rejected with a usage error") {
            val out = tempOut()
            try {
                val result =
                    KumlCli().test(
                        listOf("transform") + split.map(::script) + listOf("-t", "erm-to-exposed", "-o", out.absolutePath),
                    )
                result.statusCode shouldBe ExitCodes.USAGE
                result.stderr shouldContain "does not accept a merged UML class diagram"
                out.contents() shouldBe emptyMap()
            } finally {
                out.deleteRecursively()
            }
        }
    })
