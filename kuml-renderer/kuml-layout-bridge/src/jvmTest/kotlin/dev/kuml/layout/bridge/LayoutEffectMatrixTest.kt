package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.core.model.layout.LayoutFlow
import dev.kuml.core.model.layout.LayoutOption
import dev.kuml.core.model.layout.LayoutSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class LayoutEffectMatrixTest :
    FunSpec({
        test("every diagram art has a matrix row") {
            SpecDiagramKind.entries.forEach { kind ->
                LayoutEffectMatrix.effective.containsKey(kind) shouldBe true
                // Rows are all-or-nothing today: an option set is either fully honoured or fully ignored.
                val row = LayoutEffectMatrix.effectiveFor(kind)
                (row.isEmpty() || row == LayoutOption.entries.toSet()) shouldBe true
            }
            LayoutEffectMatrix.effective.keys shouldBe SpecDiagramKind.entries.toSet()
        }

        test("fixed-geometry arts ignore every option, layered arts none") {
            val spec = LayoutSpec(direction = LayoutFlow.Right, wrapColumns = 2, aspectRatio = 1.5, maxWidth = 900)
            listOf(
                SpecDiagramKind.UML_SEQUENCE,
                SpecDiagramKind.SYSML2_SEQ,
                SpecDiagramKind.BLUEPRINT,
                SpecDiagramKind.JOURNEY,
                SpecDiagramKind.UML_TIMING,
            ).forEach {
                LayoutSpecRunner.ignoredOptions(spec = spec, kind = it) shouldBe
                    listOf(LayoutOption.DIRECTION, LayoutOption.WRAP_COLUMNS, LayoutOption.MAX_WIDTH, LayoutOption.ASPECT_RATIO)
            }
            listOf(SpecDiagramKind.UML_CLASS, SpecDiagramKind.C4, SpecDiagramKind.ERM, SpecDiagramKind.SYSML2_BDD).forEach {
                LayoutSpecRunner.ignoredOptions(spec = spec, kind = it).shouldBeEmpty()
            }
        }

        test("W001 is reported once per option and diagram, never per element, and not for an empty block") {
            val spec = LayoutSpec(sameLayer = listOf(listOf("a", "b", "c"), listOf("d", "e")), first = listOf("a", "b"))
            val findings = LayoutSpecRunner.noEffectFindings(spec = spec, kind = SpecDiagramKind.BLUEPRINT, diagramName = "Service")
            findings.size shouldBe 2
            findings.all { it.code == LayoutCodes.NO_EFFECT } shouldBe true
            findings.all { "Service" in it.message } shouldBe true
            LayoutSpecRunner
                .noEffectFindings(
                    spec = LayoutSpec(),
                    kind = SpecDiagramKind.BLUEPRINT,
                    diagramName = "Service",
                ).shouldBeEmpty()
            LayoutSpecRunner.noEffectFindings(spec = null, kind = SpecDiagramKind.BLUEPRINT, diagramName = "Service").shouldBeEmpty()
        }

        test("validate reports ignored options and unknown ids without running a layout") {
            val spec = LayoutSpec(direction = LayoutFlow.Right, first = listOf("known", "ghost"))
            val onLayered = LayoutSpecRunner.validate(kind = SpecDiagramKind.C4, spec = spec, knownIds = setOf("known"))
            onLayered.map { it.code } shouldBe listOf(LayoutCodes.UNKNOWN_ID)
            val onFixed = LayoutSpecRunner.validate(kind = SpecDiagramKind.BLUEPRINT, spec = spec, knownIds = setOf("known"))
            onFixed.map { it.code }.toSet() shouldBe setOf(LayoutCodes.NO_EFFECT, LayoutCodes.UNKNOWN_ID)
            LayoutSpecRunner.validate(kind = SpecDiagramKind.C4, spec = null, knownIds = emptySet()).shouldBeEmpty()
        }
    })
