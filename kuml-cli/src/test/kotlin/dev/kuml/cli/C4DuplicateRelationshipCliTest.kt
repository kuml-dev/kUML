package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.SystemContextDiagram
import dev.kuml.core.script.ExtractedDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Nacharbeit 113: identical C4 relationships are `KUML-C4-W001`. [NoteChecks.of] is the funnel both `render` and `validate`
 * consume, so asserting it covers both paths; the render itself is not changed.
 */
class C4DuplicateRelationshipCliTest :
    FunSpec({
        fun rel(
            id: String,
            source: String = "a",
            target: String = "b",
            label: String = "uses",
        ) = C4Relationship(id = id, source = source, target = target, label = label, technology = "HTTPS")

        fun extracted(vararg rels: C4Relationship) =
            ExtractedDiagram.C4(
                model = C4Model(id = "m", name = "M", relationships = rels.toList()),
                diagram = SystemContextDiagram(id = "d", name = "D"),
            )

        test("identical relationships are one warning KUML-C4-W001 naming the endpoints, once") {
            val f = ValidationService.findingsOf(extracted(rel("r1"), rel("r2"))).filter { it.code == "KUML-C4-W001" }
            f shouldHaveSize 1
            f.single().severity shouldBe Severity.WARNING
            f.single().message shouldContain "a -> b"
            f.single().message shouldContain "2 occurrences"
        }

        test("relationships differing only in case or whitespace are one warning KUML-C4-W002 and no W001") {
            val all = ValidationService.findingsOf(extracted(rel("r1", label = "Uses"), rel("r2", label = "uses ")))
            val f = all.filter { it.code == "KUML-C4-W002" }
            f shouldHaveSize 1
            f.single().severity shouldBe Severity.WARNING
            f.single().message shouldContain "a -> b"
            f.single().message shouldContain "near-duplicate"
            all.filter { it.code == "KUML-C4-W001" }.shouldBeEmpty()
        }

        test("NoteChecks.of reports W002 as a non-error finding on the first relationship id") {
            val f =
                NoteChecks.of(extracted = extracted(rel("r1", label = "Uses"), rel("r2", label = "uses "))).single {
                    it.code ==
                        "KUML-C4-W002"
                }
            f.isError shouldBe false
            f.subjectId shouldBe "r1"
        }

        test("exact duplicates give W001 and no W002") {
            val all = ValidationService.findingsOf(extracted(rel("r1"), rel("r2")))
            all.filter { it.code == "KUML-C4-W002" }.shouldBeEmpty()
            all.filter { it.code == "KUML-C4-W001" } shouldHaveSize 1
        }

        test("a script with a case/whitespace variant warns KUML-C4-W002 through the DSL path") {
            val script = this::class.java.getResource("/near-duplicate-relationship-c4.kuml.kts")!!.readText()
            val f = ValidationService.validate(script = script)
            f.filter { it.code == "KUML-C4-W002" } shouldHaveSize 1
            f.filter { it.code == "KUML-C4-W001" }.shouldBeEmpty()
        }

        test("validate prints KUML-C4-W002 in category c4 and exits 0, also with --strict").config(timeout = 120.seconds) {
            val file = File("src/test/resources/near-duplicate-relationship-c4.kuml.kts")
            val text = KumlCli().test(listOf("validate", file.absolutePath))
            text.statusCode shouldBe 0
            text.output shouldContain "KUML-C4-W002"
            val json = KumlCli().test(listOf("validate", file.absolutePath, "--output", "json"))
            json.output shouldContain "\"c4\""
            KumlCli().test(listOf("validate", file.absolutePath, "--strict")).statusCode shouldBe 0
        }

        test("NoteChecks.of reports it as a non-error finding on the first relationship id") {
            val f = NoteChecks.of(extracted = extracted(rel("r1"), rel("r2"))).single { it.code == "KUML-C4-W001" }
            f.isError shouldBe false
            f.subjectId shouldBe "r1"
        }

        test("different labels or the reverse direction give no C4 finding") {
            ValidationService
                .findingsOf(extracted(rel("r1"), rel("r2", label = "reads"), rel("r3", source = "b", target = "a")))
                .filter { it.code.startsWith("KUML-C4") }
                .shouldBeEmpty()
        }

        test("a script-built model without duplicates has no C4 finding") {
            val script = this::class.java.getResource("/minimal-c4.kuml.kts")!!.readText()
            ValidationService.validate(script = script).filter { it.code.startsWith("KUML-C4") }.shouldBeEmpty()
        }

        test("a script that repeats a relationship warns through the DSL path") {
            val script = this::class.java.getResource("/duplicate-relationship-c4.kuml.kts")!!.readText()
            ValidationService.validate(script = script).filter { it.code == "KUML-C4-W001" } shouldHaveSize 1
        }

        test("validate prints KUML-C4-W001 in category c4 and exits 0, also with --strict").config(timeout = 120.seconds) {
            val file = File("src/test/resources/duplicate-relationship-c4.kuml.kts")
            val text = KumlCli().test(listOf("validate", file.absolutePath))
            text.statusCode shouldBe 0
            text.output shouldContain "KUML-C4-W001"
            val json = KumlCli().test(listOf("validate", file.absolutePath, "--output", "json"))
            json.output shouldContain "\"c4\""
            json.output shouldNotContain "\"blueprint\""
            KumlCli().test(listOf("validate", file.absolutePath, "--strict")).statusCode shouldBe 0
        }
    })
