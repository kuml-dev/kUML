package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

class ReverseCommandTest :
    FunSpec({

        test("--list-engines prints both java and kotlin") {
            val result = KumlCli().test("reverse --list-engines")
            result.statusCode shouldBe 0
            result.stdout shouldContain "java"
            result.stdout shouldContain "kotlin"
        }

        test("auto detects java majority and emits DSL on stdout") {
            val tmp = Files.createTempDirectory("rev-auto-java-")
            Files.writeString(tmp.resolve("A.java"), "public class A {}")
            Files.writeString(tmp.resolve("B.java"), "public class B {}")
            Files.writeString(tmp.resolve("C.java"), "public class C {}")
            Files.writeString(tmp.resolve("One.kt"), "class One")

            val result = KumlCli().test(listOf("reverse", tmp.toString()))
            result.statusCode shouldBe 0
            result.stdout shouldContain "classDiagram"
            result.stdout shouldContain "classOf(name = \"A\""
            result.stdout shouldContain "'java' engine"
        }

        test("auto detects kotlin majority") {
            val tmp = Files.createTempDirectory("rev-auto-kt-")
            Files.writeString(tmp.resolve("A.kt"), "class A")
            Files.writeString(tmp.resolve("B.kt"), "class B")
            Files.writeString(tmp.resolve("C.kt"), "class C")
            Files.writeString(tmp.resolve("One.java"), "public class One {}")

            val result = KumlCli().test(listOf("reverse", tmp.toString()))
            result.statusCode shouldBe 0
            result.stdout shouldContain "'kotlin' engine"
            result.stdout shouldContain "classOf(name = \"A\""
        }

        test("--lang kotlin overrides auto-detection") {
            val tmp = Files.createTempDirectory("rev-explicit-")
            Files.writeString(tmp.resolve("Foo.kt"), "class Foo")

            val result = KumlCli().test(listOf("reverse", tmp.toString(), "--lang", "kotlin"))
            result.statusCode shouldBe 0
            result.stdout shouldContain "'kotlin' engine"
        }

        fun kotlinFixture(): java.nio.file.Path {
            val tmp = Files.createTempDirectory("rev-filter-kt-")
            Files.writeString(tmp.resolve("Foo.kt"), "class Foo")
            Files.writeString(tmp.resolve("FooTest.kt"), "class WidgetTest")
            Files.writeString(tmp.resolve("BarSpec.kt"), "class Gadget")
            return tmp
        }

        test("--exclude drops matching kotlin files from the model") {
            val tmp = kotlinFixture()
            val result = KumlCli().test(listOf("reverse", tmp.toString(), "--lang", "kotlin", "--exclude", "*Test.kt"))
            result.statusCode shouldBe 0
            result.stdout shouldContain "classOf(name = \"Foo\""
            result.stdout shouldContain "classOf(name = \"Gadget\""
            result.stdout shouldNotContain "WidgetTest"
        }

        test("two --exclude options drop both files") {
            val tmp = kotlinFixture()
            val result =
                KumlCli().test(
                    listOf("reverse", tmp.toString(), "--lang", "kotlin", "--exclude", "*Test.kt", "--exclude", "*Spec.kt"),
                )
            result.statusCode shouldBe 0
            result.stdout shouldContain "classOf(name = \"Foo\""
            result.stdout shouldNotContain "WidgetTest"
            result.stdout shouldNotContain "Gadget"
        }

        test("--include restricts kotlin files") {
            val tmp = kotlinFixture()
            val result = KumlCli().test(listOf("reverse", tmp.toString(), "--lang", "kotlin", "--include", "Foo.kt"))
            result.statusCode shouldBe 0
            result.stdout shouldContain "classOf(name = \"Foo\""
            result.stdout shouldNotContain "Gadget"
        }

        test("unknown --lang exits with REVERSE_ENGINE_NOT_FOUND") {
            val tmp = Files.createTempDirectory("rev-unknown-")
            Files.writeString(tmp.resolve("Foo.kt"), "class Foo")

            val result = KumlCli().test(listOf("reverse", tmp.toString(), "--lang", "elixir"))
            result.statusCode shouldBe ExitCodes.REVERSE_ENGINE_NOT_FOUND
            result.stderr shouldContain "Unknown reverse engine 'elixir'"
        }

        test("empty source directory exits with REVERSE_NO_SOURCES") {
            val tmp = Files.createTempDirectory("rev-empty-")
            val result = KumlCli().test(listOf("reverse", tmp.toString()))
            result.statusCode shouldBe ExitCodes.REVERSE_NO_SOURCES
            result.stderr shouldContain "No source files"
        }

        test("--output writes the DSL to a file") {
            val tmp = Files.createTempDirectory("rev-output-")
            Files.writeString(tmp.resolve("Foo.kt"), "class Foo")
            val outFile = Files.createTempFile("rev-out-", ".kuml.kts").toFile()

            val result = KumlCli().test(listOf("reverse", tmp.toString(), "--output", outFile.absolutePath))
            result.statusCode shouldBe 0
            outFile.exists() shouldBe true
            val text = outFile.readText()
            text shouldContain "classDiagram"
            text shouldContain "classOf(name = \"Foo\""
        }

        test("missing source-dir without --list-engines exits with SCRIPT_ERROR") {
            val result = KumlCli().test("reverse")
            result.statusCode shouldBe ExitCodes.SCRIPT_ERROR
            result.stderr shouldContain "Missing argument"
        }

        test("--model-name is reflected in classDiagram name") {
            val tmp = Files.createTempDirectory("rev-name-")
            Files.writeString(tmp.resolve("Foo.kt"), "class Foo")
            val result = KumlCli().test(listOf("reverse", tmp.toString(), "--model-name", "MyDomain"))
            result.statusCode shouldBe 0
            result.stdout shouldContain "classDiagram(name = \"MyDomain\")"
        }

        test("diagnostic summary is printed to stderr") {
            val tmp = Files.createTempDirectory("rev-diag-")
            // Top-level function triggers REV-K-011 diagnostic
            Files.writeString(
                tmp.resolve("Mixed.kt"),
                """
                class A
                fun topLevelFunc() {}
                """.trimIndent(),
            )
            val result = KumlCli().test(listOf("reverse", tmp.toString(), "--lang", "kotlin"))
            result.statusCode shouldBe 0
            // Either summary or some INFO message should be on stderr
            result.stderr.shouldNotBeEmpty()
        }
    })
