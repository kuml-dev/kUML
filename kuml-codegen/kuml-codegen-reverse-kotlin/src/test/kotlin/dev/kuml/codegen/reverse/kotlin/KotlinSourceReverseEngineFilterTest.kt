package dev.kuml.codegen.reverse.kotlin

import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.ReverseResult
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

class KotlinSourceReverseEngineFilterTest :
    FunSpec({

        fun fixture(extra: Map<String, String> = emptyMap()): Path {
            val tmp = Files.createTempDirectory("kuml-reverse-kt-filter-")
            val files =
                mapOf(
                    "pkg/Foo.kt" to "package pkg\nclass Foo",
                    "pkg/FooTest.kt" to "package pkg\nclass FooTest",
                    "pkg/BarSpec.kt" to "package pkg\nclass BarSpec",
                ) + extra
            files.forEach { (rel, code) ->
                val p = tmp.resolve(rel)
                Files.createDirectories(p.parent)
                Files.writeString(p, code)
            }
            return tmp
        }

        fun run(
            root: Path,
            include: List<String> = ReverseRequest.DEFAULT_INCLUDE_GLOBS,
            exclude: List<String> = emptyList(),
        ): ReverseResult =
            runBlocking {
                KotlinSourceReverseEngine().analyze(
                    ReverseRequest(sourceRoots = listOf(root), includeGlobs = include, excludeGlobs = exclude),
                )
            }

        fun names(result: ReverseResult): List<String> {
            val ok = TestSupport.success(result)
            return (ok.model.root as KumlDiagram)
                .elements
                .filterIsInstance<UmlClass>()
                .map { it.name }
                .sorted()
        }

        test("without globs all kt files are analysed (java default include is ignored)") {
            names(run(fixture())) shouldBe listOf("BarSpec", "Foo", "FooTest")
        }

        test("exclude by file name removes the matching file from the model") {
            names(run(fixture(), exclude = listOf("*Test.kt"))) shouldBe listOf("BarSpec", "Foo")
        }

        test("two excludes leave only Foo") {
            names(run(fixture(), exclude = listOf("*Test.kt", "*Spec.kt"))) shouldBe listOf("Foo")
        }

        test("path exclude works") {
            names(run(fixture(), exclude = listOf("**/pkg/*Test.kt"))) shouldBe listOf("BarSpec", "Foo")
        }

        test("include narrows and exclude wins") {
            names(run(fixture(), include = listOf("Foo*.kt"), exclude = listOf("*Test.kt"))) shouldBe listOf("Foo")
        }

        test("explicit java include is ignored for kotlin") {
            names(run(fixture(), include = listOf("**/*.java"))) shouldBe listOf("BarSpec", "Foo", "FooTest")
        }

        test("filesAnalysed counts only selected files") {
            TestSupport.success(run(fixture(), exclude = listOf("*Test.kt"))).filesAnalysed shouldBe 2
        }

        test("everything excluded yields REV-CORE-001 mentioning globs") {
            val result = run(fixture(), exclude = listOf("*.kt"))
            val failure = result as ReverseResult.Failure
            failure.errors.map { it.code } shouldContain "REV-CORE-001"
            failure.errors.first().message shouldContain "include/exclude"
        }

        test("empty directory keeps the original message") {
            val failure = run(Files.createTempDirectory("kuml-empty-")) as ReverseResult.Failure
            failure.errors.first().code shouldBe "REV-CORE-001"
            failure.errors.first().message shouldContain "No Kotlin source files found"
        }

        test("reference to an excluded type does not crash") {
            val root =
                fixture(
                    mapOf("pkg/Foo.kt" to "package pkg\nclass Foo(val t: FooTest)"),
                )
            names(run(root, exclude = listOf("*Test.kt"))) shouldBe listOf("BarSpec", "Foo")
        }

        test("invalid glob yields REV-CORE-003") {
            val failure = run(fixture(), exclude = listOf("{a")) as ReverseResult.Failure
            failure.errors.first().code shouldBe "REV-CORE-003"
        }

        test("kts files are not analysed") {
            names(run(fixture(mapOf("script.kts" to "class InScript")))) shouldBe listOf("BarSpec", "Foo", "FooTest")
        }
    })
