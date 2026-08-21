package dev.kuml.vaultexamples

import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.fromKumlDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlRelationship
import dev.kuml.uml.dsl.print.UmlModelDslPrinter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * V3.2.x, Branch 2.3 (design review, Fund 5) — measures the round-trip data loss of
 * [AnyKumlModel.Uml.fromKumlDiagram], the exact function `AiPanelState.seedEditingContextFromScript()`
 * uses to derive the AI panel's editing context from the currently open `*.kuml.kts` script.
 *
 * The round-trip under test: `script → eval → extractAny → fromKumlDiagram → toKumlModel →
 * UmlModelDslPrinter.print → eval → extractAny` — i.e. exactly what happens once per AI-panel
 * turn, immediately followed by a re-parse to see what the AI would actually be shown.
 *
 * This test does NOT fix the known gap (a third `AnyKumlModel.Uml` bucket for [UmlComment] plus
 * matching changes in DeepCopy/ModelMutationRouter/ScriptSerializer/PatchApplyEngine is out of
 * scope for this wave — see [AnyKumlModel.Uml.fromKumlDiagram]'s KDoc). It exists so the gap is
 * pinned down as an explicit, named regression guard in `./gradlew check` output instead of only
 * living in a code comment — and so every OTHER class-diagram vault example is verified to
 * roundtrip losslessly (at the level of top-level element names + relationship count), which is
 * the claim this wave is actually allowed to make.
 */
class AiContextSeedingRoundtripTest :
    StringSpec({
        val examples = VaultExampleLoader.loadFromClasspath()

        // Every example whose script produces an ExtractedDiagram.Uml — matching exactly what
        // AiPanelState.seedEditingContextFromScript() accepts (it does NOT restrict by
        // DiagramType, only by "is this an ExtractedDiagram.Uml at all"). "11 UML Paket –
        // Domain Modules" is a DiagramType.PACKAGE script, not CLASS — it needs this
        // unrestricted set to be found for its own dedicated test below.
        val allUmlExamples = examples.mapNotNull { example -> extractUmlDiagram(example.kumlScript)?.let { example to it } }

        // The general "lossless roundtrip" sweep below is intentionally narrowed to
        // DiagramType.CLASS — Fund 5 documents that non-class UML diagrams (state machine,
        // sequence/interaction) lose further element kinds (regions, interaction fragments)
        // that this test does not attempt to measure; only class diagrams are asserted lossless.
        val classDiagramExamples = allUmlExamples.filter { (_, diagram) -> diagram.type == DiagramType.CLASS }

        // Fixtures with a DOCUMENTED, ACCEPTED loss — asserted explicitly below (see the two
        // dedicated tests), not silently included in (or silently excluded from) the general
        // lossless-roundtrip sweep.
        val knownLossBaseNames = setOf("01 UML Klasse – Order Domain", "11 UML Paket – Domain Modules")

        if (classDiagramExamples.isEmpty()) {
            println(
                "[ai-context-seeding-roundtrip] Keine Klassendiagramm-Beispiele auf dem Classpath " +
                    "gefunden — stelle sicher, dass src/test/resources/vault-examples/ befüllt ist.",
            )
        }

        classDiagramExamples
            .filter { (example, _) -> example.baseName !in knownLossBaseNames }
            .forEach { (example, diagram) ->
                "roundtrips ${example.baseName} without element-name or relationship-count loss" {
                    val before = AnyKumlModel.Uml.fromKumlDiagram(diagram)
                    val dsl = UmlModelDslPrinter.print(before.toKumlModel())
                    val after = extractUmlClassDiagram(dsl)
                    after.shouldNotBeNull()

                    val beforeNames = before.elements.map { it.name }.toSet()
                    val afterNamedElements = after.elements.filterIsInstance<UmlNamedElement>()
                    val afterNames = afterNamedElements.map { it.name }.toSet()
                    afterNames shouldBe beforeNames

                    val afterRelCount = after.elements.count { it is UmlRelationship }
                    afterRelCount shouldBe before.relationships.size
                }
            }

        "01 UML Klasse – Order Domain: the UmlComment present before seeding is silently dropped after (documented loss, Fund 5)" {
            val (_, diagram) = classDiagramExamples.first { (e, _) -> e.baseName == "01 UML Klasse – Order Domain" }
            // Sanity check on the fixture itself — if this ever fails, the vault example changed
            // and no longer exercises the comment() DSL call this test depends on.
            diagram.elements.filterIsInstance<UmlComment>().size shouldBe 1

            val before = AnyKumlModel.Uml.fromKumlDiagram(diagram)
            val dsl = UmlModelDslPrinter.print(before.toKumlModel())
            val after = extractUmlClassDiagram(dsl)
            after.shouldNotBeNull()

            // AnyKumlModel.Uml has no bucket for UmlComment (it is neither a UmlNamedElement nor
            // a UmlRelationship) — fromKumlDiagram() drops it silently, so it can never reappear
            // after the round-trip through toKumlModel()/UmlModelDslPrinter.
            after.elements.filterIsInstance<UmlComment>().shouldBeEmpty()
        }

        "11 UML Paket – Domain Modules: UmlPackage members are silently dropped after seeding (documented loss, Fund 5)" {
            val (_, diagram) = allUmlExamples.first { (e, _) -> e.baseName == "11 UML Paket – Domain Modules" }
            val beforePackages = diagram.elements.filterIsInstance<UmlPackage>()
            // Sanity check on the fixture itself (shared / shop / payment packages).
            beforePackages.size shouldBe 3

            val before = AnyKumlModel.Uml.fromKumlDiagram(diagram)
            val dsl = UmlModelDslPrinter.print(before.toKumlModel())
            // Unrestricted extraction here (not extractUmlClassDiagram): the round-tripped
            // AnyKumlModel.Uml still carries diagramType = "PACKAGE" (fromKumlDiagram/toKumlModel
            // preserve it verbatim), so the re-evaluated script is still a PACKAGE diagram, not
            // a CLASS one.
            val after = extractUmlDiagram(dsl)
            after.shouldNotBeNull()

            // UmlModelDslPrinter treats UmlPackage as out of scope (emits a `// TODO` comment
            // instead of a builder call, see its class KDoc) — the package AND every classifier
            // nested inside it vanish from the printed DSL, and therefore from the re-eval too.
            after.elements.filterIsInstance<UmlPackage>().shouldBeEmpty()
        }
    })

/** Evaluates [script] and returns its [KumlDiagram] if it produces an [ExtractedDiagram.Uml] (any UML diagram type). */
private fun extractUmlDiagram(script: String): KumlDiagram? {
    val evalResult = runCatching { KumlScriptHost.eval(code = script) }.getOrNull() ?: return null
    val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
    if (errors.isNotEmpty()) return null
    val success = evalResult as? ResultWithDiagnostics.Success ?: return null
    val extracted =
        runCatching {
            DiagramExtractor.extractAny(returnValue = success.value.returnValue, input = File("inline.kuml.kts"))
        }.getOrNull() ?: return null
    return (extracted as? ExtractedDiagram.Uml)?.diagram
}

/** Evaluates [script] and returns its [KumlDiagram] only if it is a UML CLASS diagram; else null. */
private fun extractUmlClassDiagram(script: String): KumlDiagram? = extractUmlDiagram(script)?.takeIf { it.type == DiagramType.CLASS }
