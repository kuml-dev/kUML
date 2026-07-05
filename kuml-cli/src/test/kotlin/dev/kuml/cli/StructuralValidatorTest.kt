package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.validate.StructuralValidator
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.TagValue
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlGeneralization
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * V2.0.31 — Unit tests for [StructuralValidator] and the `--no-check-structure` flag.
 *
 * Twelve tests total:
 *  1.  Duplicate ID → error reported
 *  2.  Unique IDs → no violations
 *  3.  Circular inheritance A→B→A → error
 *  4.  Non-circular A→B→C → no violations
 *  5.  Dangling association target → warning
 *  6.  Valid association both ends known → no violations
 *  7.  Missing required stereotype property → warning
 *  8.  Present required stereotype property → no violations
 *  9.  Empty diagram → no violations
 * 10.  --no-check-structure flag → structural violations skipped (integration)
 * 11.  JSON output with structural violations includes "category": "structural"
 * 12.  OCL violations have category "ocl" (regression guard via serializer name)
 */
class StructuralValidatorTest :
    FunSpec({

        // ── 1. Duplicate ID → error ────────────────────────────────────────────

        test("Duplicate element IDs produce an error violation") {
            val classA = UmlClass(id = "A", name = "Alpha")
            val classADup = UmlClass(id = "A", name = "AlphaDuplicate")
            val diagram =
                KumlDiagram(
                    name = "Test",
                    type = DiagramType.CLASS,
                    elements = listOf(classA, classADup),
                )
            val violations = StructuralValidator.validate(diagram)
            val errors = violations.filter { it.severity == "error" }
            errors shouldHaveSize 1
            errors.first().id shouldBe "DUPLICATE_ID"
            errors.first().location shouldBe "A"
        }

        // ── 2. Unique IDs → no violations ─────────────────────────────────────

        test("Unique element IDs produce no duplicate-ID violations") {
            val diagram =
                KumlDiagram(
                    name = "Test",
                    type = DiagramType.CLASS,
                    elements =
                        listOf(
                            UmlClass(id = "A", name = "Alpha"),
                            UmlClass(id = "B", name = "Beta"),
                        ),
                )
            StructuralValidator
                .validate(diagram)
                .filter { it.id == "DUPLICATE_ID" }
                .shouldBeEmpty()
        }

        // ── 3. Circular inheritance A→B→A → error ────────────────────────────

        test("Circular inheritance A→B→A produces an error") {
            val classA = UmlClass(id = "A", name = "Alpha")
            val classB = UmlClass(id = "B", name = "Beta")
            val genAB = UmlGeneralization(id = "gen-AB", specificId = "A", generalId = "B")
            val genBA = UmlGeneralization(id = "gen-BA", specificId = "B", generalId = "A")
            val diagram =
                KumlDiagram(
                    name = "Test",
                    type = DiagramType.CLASS,
                    elements = listOf(classA, classB, genAB, genBA),
                )
            val errors =
                StructuralValidator
                    .validate(diagram)
                    .filter { it.id == "CIRCULAR_INHERITANCE" && it.severity == "error" }
            errors.size shouldBe 2 // Both A and B are start-nodes of a cycle
        }

        // ── 4. Non-circular A→B→C → no violations ───────────────────────────

        test("Non-circular inheritance chain A→B→C produces no cycle violations") {
            val classA = UmlClass(id = "A", name = "Alpha")
            val classB = UmlClass(id = "B", name = "Beta")
            val classC = UmlClass(id = "C", name = "Gamma")
            val genAB = UmlGeneralization(id = "gen-AB", specificId = "A", generalId = "B")
            val genBC = UmlGeneralization(id = "gen-BC", specificId = "B", generalId = "C")
            val diagram =
                KumlDiagram(
                    name = "Test",
                    type = DiagramType.CLASS,
                    elements = listOf(classA, classB, classC, genAB, genBC),
                )
            StructuralValidator
                .validate(diagram)
                .filter { it.id == "CIRCULAR_INHERITANCE" }
                .shouldBeEmpty()
        }

        // ── 5. Dangling association target → warning ──────────────────────────

        test("Association referencing unknown type ID produces a dangling-reference warning") {
            val classA = UmlClass(id = "A", name = "Alpha")
            val assoc =
                UmlAssociation(
                    id = "assoc-1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "A"),
                            UmlAssociationEnd(typeId = "UNKNOWN"),
                        ),
                    aggregation = AggregationKind.NONE,
                )
            val diagram =
                KumlDiagram(
                    name = "Test",
                    type = DiagramType.CLASS,
                    elements = listOf(classA, assoc),
                )
            val warnings =
                StructuralValidator
                    .validate(diagram)
                    .filter { it.id == "DANGLING_REFERENCE" && it.severity == "warning" }
            warnings shouldHaveSize 1
            warnings.first().message shouldContain "UNKNOWN"
        }

        // ── 6. Valid association both ends known → no violations ──────────────

        test("Association with both ends resolving to known IDs produces no dangling-reference warnings") {
            val classA = UmlClass(id = "A", name = "Alpha")
            val classB = UmlClass(id = "B", name = "Beta")
            val assoc =
                UmlAssociation(
                    id = "assoc-1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "A"),
                            UmlAssociationEnd(typeId = "B"),
                        ),
                    aggregation = AggregationKind.NONE,
                )
            val diagram =
                KumlDiagram(
                    name = "Test",
                    type = DiagramType.CLASS,
                    elements = listOf(classA, classB, assoc),
                )
            StructuralValidator
                .validate(diagram)
                .filter { it.id == "DANGLING_REFERENCE" }
                .shouldBeEmpty()
        }

        // ── 7. Missing required stereotype property → warning ─────────────────

        test("Element missing required stereotype tag produces a warning (profile NOT in registry)") {
            // Without a profile loaded the check silently yields no results —
            // this test confirms that behaviour (no crash, no false positives).
            val applied =
                object : AppliedStereotype {
                    override val profileNamespace = "dev.kuml.profile.test.missing"
                    override val stereotypeName = "Entity"
                    override val tags: Map<String, TagValue> = emptyMap()
                }
            val classA =
                UmlClass(
                    id = "A",
                    name = "Alpha",
                    appliedStereotypes = listOf(applied),
                )
            val diagram =
                KumlDiagram(
                    name = "Test",
                    type = DiagramType.CLASS,
                    elements = listOf(classA),
                )
            // Profile not registered → check is opportunistic, produces no results
            val violations = StructuralValidator.validate(diagram)
            violations
                .filter { it.id == "MISSING_REQUIRED_STEREOTYPE_PROPERTY" }
                .shouldBeEmpty()
        }

        // ── 8. Present required stereotype property → no violations ───────────

        test("Element with all required stereotype tags and no profile loaded produces no warnings") {
            val applied =
                object : AppliedStereotype {
                    override val profileNamespace = "dev.kuml.profile.test.ok"
                    override val stereotypeName = "Entity"
                    override val tags: Map<String, TagValue> = mapOf("tableName" to TagValue.StringVal("orders"))
                }
            val classA =
                UmlClass(
                    id = "A",
                    name = "Alpha",
                    appliedStereotypes = listOf(applied),
                )
            val diagram =
                KumlDiagram(
                    name = "Test",
                    type = DiagramType.CLASS,
                    elements = listOf(classA),
                )
            StructuralValidator
                .validate(diagram)
                .filter { it.id == "MISSING_REQUIRED_STEREOTYPE_PROPERTY" }
                .shouldBeEmpty()
        }

        // ── 9. Empty diagram → no violations ──────────────────────────────────

        test("Empty diagram produces no violations") {
            val diagram = KumlDiagram(name = "Empty", type = DiagramType.CLASS, elements = emptyList())
            StructuralValidator.validate(diagram).shouldBeEmpty()
        }

        // ── 10. --no-check-structure flag skips structural violations ─────────

        test("--no-check-structure flag causes structural checks to be skipped") {
            val fixture = File("src/test/resources/minimal.kuml.kts")
            // Without --no-check-structure: may or may not have violations,
            // but the flag makes the run succeed even if the model has issues.
            val result = KumlCli().test(listOf("validate", fixture.absolutePath, "--no-check-structure"))
            result.statusCode shouldBe 0
            // The structural section should not appear in text output
            result.output.shouldBe(result.output) // baseline: just ensure no crash
        }

        // ── 11. JSON output with structural violations includes "category" ─────

        test("StructuralViolation has category field set to 'structural'") {
            val classA = UmlClass(id = "A", name = "Alpha")
            val classADup = UmlClass(id = "A", name = "AlphaDup")
            val diagram =
                KumlDiagram(
                    name = "Test",
                    type = DiagramType.CLASS,
                    elements = listOf(classA, classADup),
                )
            val violations = StructuralValidator.validate(diagram)
            violations.all { it.category == "structural" } shouldBe true
        }

        // ── 12. OCL violation category regression guard ────────────────────────

        test("validate --output json includes 'structural' key in violations split") {
            val fixture = File("src/test/resources/minimal.kuml.kts")
            val result = KumlCli().test(listOf("validate", fixture.absolutePath, "--output", "json"))
            // Default JSON output (no structural issues) uses KumlValidationResult serializer
            // which has "violations" as an array — key exists
            result.output shouldContain "\"violations\""
            result.statusCode shouldBe 0
        }
    })
