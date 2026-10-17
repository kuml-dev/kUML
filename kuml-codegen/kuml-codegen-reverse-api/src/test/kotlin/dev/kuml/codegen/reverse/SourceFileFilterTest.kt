package dev.kuml.codegen.reverse

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class SourceFileFilterTest :
    FunSpec({

        fun filter(
            include: List<String> = emptyList(),
            exclude: List<String> = emptyList(),
        ) = SourceFileFilter(includeGlobs = include, excludeGlobs = exclude)

        test("file-name glob matches name in root and subdirectories") {
            val f = filter(exclude = listOf("*Test.kt"))
            f.accepts("FooTest.kt") shouldBe false
            f.accepts("pkg/FooTest.kt") shouldBe false
            f.accepts("Foo.kt") shouldBe true
        }

        test("path globs match relative path, including root-level files") {
            val f = filter(exclude = listOf("**/test/**"))
            f.accepts("src/test/A.kt") shouldBe false
            f.accepts("test/A.kt") shouldBe false
            f.accepts("src/main/A.kt") shouldBe true
            filter(exclude = listOf("src/test/**")).accepts("src/test/A.kt") shouldBe false
            filter(exclude = listOf("src/test/**")).accepts("other/src/test/A.kt") shouldBe true
        }

        test("leading double-star slash also matches root file") {
            filter(include = listOf("**/Foo.kt")).accepts("Foo.kt") shouldBe true
            filter(include = listOf("**/Foo.kt")).accepts("a/b/Foo.kt") shouldBe true
        }

        test("windows separators are normalized") {
            filter(exclude = listOf("*Test.kt")).accepts("pkg\\FooTest.kt") shouldBe false
            filter(exclude = listOf("**\\test\\**")).accepts("src\\test\\A.kt") shouldBe false
        }

        test("empty includes accept all, exclude wins over include") {
            filter().accepts("Any.kt") shouldBe true
            val f = filter(include = listOf("Foo*.kt"), exclude = listOf("*Test.kt"))
            f.accepts("Foo.kt") shouldBe true
            f.accepts("FooTest.kt") shouldBe false
            f.accepts("Bar.kt") shouldBe false
        }

        test("alternatives, question mark and character classes") {
            val f = filter(include = listOf("{A,B}?.kt"))
            f.accepts("A1.kt") shouldBe true
            f.accepts("B2.kt") shouldBe true
            f.accepts("C1.kt") shouldBe false
            filter(include = listOf("[ab].kt")).accepts("a.kt") shouldBe true
            filter(include = listOf("[ab].kt")).accepts("c.kt") shouldBe false
        }

        test("single star does not cross directories, matching is case-sensitive") {
            filter(include = listOf("src/*.kt")).accepts("src/a/B.kt") shouldBe false
            filter(include = listOf("*.KT")).accepts("a.kt") shouldBe false
        }

        test("invalid globs are rejected") {
            shouldThrow<IllegalArgumentException> { SourceFileFilter(includeGlobs = listOf("{a,b"), excludeGlobs = emptyList()) }
            shouldThrow<IllegalArgumentException> { SourceFileFilter(includeGlobs = emptyList(), excludeGlobs = listOf("[ab")) }
            shouldThrow<IllegalArgumentException> { SourceFileFilter(includeGlobs = emptyList(), excludeGlobs = listOf("a}")) }
            shouldThrow<IllegalArgumentException> { SourceFileFilter(includeGlobs = listOf(""), excludeGlobs = emptyList()) }
            shouldThrow<IllegalArgumentException> { SourceFileFilter(includeGlobs = emptyList(), excludeGlobs = listOf("a".repeat(2000))) }
        }

        test("select walks roots, filters by extension and glob, is sorted and deduplicated") {
            val tmp = Files.createTempDirectory("filter-select-")

            fun touch(rel: String): Path {
                val p = tmp.resolve(rel)
                Files.createDirectories(p.parent)
                Files.writeString(p, "x")
                return p
            }
            val foo = touch("pkg/Foo.kt")
            touch("pkg/FooTest.kt")
            val bar = touch("Bar.kt")
            touch("pkg/Notes.txt")
            val sub = tmp.resolve("pkg")

            val f = filter(exclude = listOf("*Test.kt"))
            val result = f.select(roots = listOf(tmp, sub), extensions = setOf("kt"))
            result.map { it.toRealPath() } shouldContainExactly listOf(bar, foo).map { it.toRealPath() }.sortedBy { it.toString() }
        }

        test("a file can be a root; non-existing roots are skipped") {
            val tmp = Files.createTempDirectory("filter-file-root-")
            val file = tmp.resolve("Solo.kt")
            Files.writeString(file, "x")
            val f = filter(include = listOf("Solo.kt"))
            f.select(roots = listOf(file, tmp.resolve("missing")), extensions = setOf("kt")).size shouldBe 1
            filter(exclude = listOf("Solo.kt")).select(roots = listOf(file), extensions = setOf("kt")).size shouldBe 0
        }
    })
