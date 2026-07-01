package dev.kuml.layout.bridge

import dev.kuml.blueprint.model.BlueprintDiagramFull
import dev.kuml.blueprint.model.BlueprintGridConstants
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.JourneyDiagram
import dev.kuml.blueprint.model.Phase
import dev.kuml.layout.bridge.blueprint.BlueprintLayoutBridge
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Verifies that [BlueprintLayoutBridge] computes cell coordinates numerically
 * identical to the geometry implied by [BlueprintGridConstants].
 *
 * These tests act as a ratchet: if a constant in [BlueprintGridConstants]
 * changes, the expected values below will fail immediately, surfacing any
 * divergence before it reaches the SVG renderer.
 *
 * V3.1.23
 */
class BlueprintLayoutBridgeTest :
    FunSpec({

        val bridge = BlueprintLayoutBridge()

        // ── Shared model helpers ──────────────────────────────────────────────

        fun twoPhaseModel(): BlueprintModel =
            BlueprintModel(
                name = "Test",
                phases =
                    listOf(
                        Phase(id = "p1", name = "Awareness", order = 0),
                        Phase(id = "p2", name = "Purchase", order = 1),
                    ),
            )

        fun threePhaseModel(): BlueprintModel =
            BlueprintModel(
                name = "Test",
                phases =
                    listOf(
                        Phase(id = "p1", name = "A", order = 0),
                        Phase(id = "p2", name = "B", order = 1),
                        Phase(id = "p3", name = "C", order = 2),
                    ),
            )

        // ── Cell count ───────────────────────────────────────────────────────

        test("JourneyDiagram: 2 phases × 1 visible layer = 2 cells") {
            val diagram =
                JourneyDiagram(
                    name = "Journey",
                    visibleLayers = setOf(BlueprintLayer.CUSTOMER_ACTIONS),
                )
            val layout = bridge.layout(twoPhaseModel(), diagram)
            layout.cells shouldHaveSize 2
        }

        test("BlueprintDiagramFull: 2 phases × all 4 layers = 8 cells") {
            val diagram = BlueprintDiagramFull(name = "Blueprint")
            val layout = bridge.layout(twoPhaseModel(), diagram)
            // All 4 BlueprintLayer values × 2 phases
            layout.cells shouldHaveSize (BlueprintLayer.entries.size * 2)
        }

        // ── Cell coordinates match BlueprintGridConstants ────────────────────

        test("first cell x aligns with PADDING + LABEL_COLUMN_WIDTH") {
            val expectedX = BlueprintGridConstants.PADDING + BlueprintGridConstants.LABEL_COLUMN_WIDTH
            val diagram =
                JourneyDiagram(
                    name = "Journey",
                    visibleLayers = setOf(BlueprintLayer.CUSTOMER_ACTIONS),
                )
            val layout = bridge.layout(twoPhaseModel(), diagram)
            val firstCell = layout.cells.first { it.phaseId == "p1" }
            firstCell.x shouldBe (expectedX plusOrMinus 0.001)
        }

        test("second-phase cell x = first cell x + COLUMN_WIDTH") {
            val diagram =
                JourneyDiagram(
                    name = "Journey",
                    visibleLayers = setOf(BlueprintLayer.CUSTOMER_ACTIONS),
                )
            val layout = bridge.layout(twoPhaseModel(), diagram)
            val cell1 = layout.cells.first { it.phaseId == "p1" }
            val cell2 = layout.cells.first { it.phaseId == "p2" }
            (cell2.x - cell1.x) shouldBe (BlueprintGridConstants.COLUMN_WIDTH plusOrMinus 0.001)
        }

        test("second-row cell y = first-row cell y + ROW_HEIGHT") {
            val diagram =
                BlueprintDiagramFull(
                    name = "Blueprint",
                    visibleLayers = setOf(BlueprintLayer.CUSTOMER_ACTIONS, BlueprintLayer.FRONTSTAGE),
                )
            val layout = bridge.layout(twoPhaseModel(), diagram)
            val row0 = layout.cells.filter { it.layer == BlueprintLayer.CUSTOMER_ACTIONS }
            val row1 = layout.cells.filter { it.layer == BlueprintLayer.FRONTSTAGE }
            val dy = row1.first().y - row0.first().y
            dy shouldBe (BlueprintGridConstants.ROW_HEIGHT plusOrMinus 0.001)
        }

        test("cell width equals COLUMN_WIDTH") {
            val diagram = JourneyDiagram(name = "Journey")
            val layout = bridge.layout(twoPhaseModel(), diagram)
            layout.cells.forEach { cell ->
                cell.width shouldBe (BlueprintGridConstants.COLUMN_WIDTH plusOrMinus 0.001)
            }
        }

        test("cell height equals ROW_HEIGHT") {
            val diagram = JourneyDiagram(name = "Journey")
            val layout = bridge.layout(twoPhaseModel(), diagram)
            layout.cells.forEach { cell ->
                cell.height shouldBe (BlueprintGridConstants.ROW_HEIGHT plusOrMinus 0.001)
            }
        }

        // ── Canvas dimensions ─────────────────────────────────────────────────

        test("canvasWidth = PADDING + LABEL_COLUMN_WIDTH + n * COLUMN_WIDTH + PADDING") {
            val n = 3
            val expected =
                BlueprintGridConstants.PADDING +
                    BlueprintGridConstants.LABEL_COLUMN_WIDTH +
                    n * BlueprintGridConstants.COLUMN_WIDTH +
                    BlueprintGridConstants.PADDING
            val diagram = JourneyDiagram(name = "Journey")
            val layout = bridge.layout(threePhaseModel(), diagram)
            layout.canvasWidth shouldBe (expected plusOrMinus 0.001)
        }

        // ── Emotion band ──────────────────────────────────────────────────────

        test("JourneyDiagram with showEmotionCurve: emotionBand is not null") {
            val diagram = JourneyDiagram(name = "Journey", showEmotionCurve = true)
            val layout = bridge.layout(twoPhaseModel(), diagram)
            layout.emotionBand shouldNotBe null
        }

        test("JourneyDiagram with showEmotionCurve=false: emotionBand is null") {
            val diagram = JourneyDiagram(name = "Journey", showEmotionCurve = false)
            val layout = bridge.layout(twoPhaseModel(), diagram)
            layout.emotionBand shouldBe null
        }

        test("emotionBand height equals EMOTION_BAND_HEIGHT") {
            val diagram = JourneyDiagram(name = "Journey", showEmotionCurve = true)
            val layout = bridge.layout(twoPhaseModel(), diagram)
            val band = layout.emotionBand!!
            (band.endInclusive - band.start) shouldBe
                (BlueprintGridConstants.EMOTION_BAND_HEIGHT plusOrMinus 0.001)
        }

        test("gridTop shifts down by EMOTION_BAND_HEIGHT when emotion curve shown") {
            val withEmotion = JourneyDiagram(name = "With", showEmotionCurve = true)
            val withoutEmotion = JourneyDiagram(name = "Without", showEmotionCurve = false)
            val layoutWith = bridge.layout(twoPhaseModel(), withEmotion)
            val layoutWithout = bridge.layout(twoPhaseModel(), withoutEmotion)
            val dy = layoutWith.cells.first().y - layoutWithout.cells.first().y
            dy shouldBe (BlueprintGridConstants.EMOTION_BAND_HEIGHT plusOrMinus 0.001)
        }

        // ── Column centres ────────────────────────────────────────────────────

        test("columnCenters count equals phase count") {
            val diagram = JourneyDiagram(name = "Journey")
            val layout = bridge.layout(threePhaseModel(), diagram)
            layout.columnCenters shouldHaveSize 3
        }

        test("first columnCenter = first cell x + COLUMN_WIDTH/2") {
            val diagram = JourneyDiagram(name = "Journey")
            val layout = bridge.layout(twoPhaseModel(), diagram)
            val expectedCenter = layout.cells.first { it.phaseId == "p1" }.x + BlueprintGridConstants.COLUMN_WIDTH / 2
            layout.columnCenters.first() shouldBe (expectedCenter plusOrMinus 0.001)
        }

        // ── Layer order ───────────────────────────────────────────────────────

        test("layerOrder follows BlueprintLayer.entries declaration order") {
            val diagram = BlueprintDiagramFull(name = "Blueprint")
            val layout = bridge.layout(twoPhaseModel(), diagram)
            layout.layerOrder shouldBe BlueprintLayer.entries.toList()
        }

        test("JourneyDiagram: layerOrder contains only CUSTOMER_ACTIONS by default") {
            val diagram = JourneyDiagram(name = "Journey")
            val layout = bridge.layout(twoPhaseModel(), diagram)
            layout.layerOrder shouldBe listOf(BlueprintLayer.CUSTOMER_ACTIONS)
        }
    })
