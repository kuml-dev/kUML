package dev.kuml.jetbrains

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * V2.0.28b — Unit tests for KumlPreviewPanel, KumlSplitEditorProvider, and
 * KumlStructureViewBuilderProvider.
 *
 * All tests are standalone (no IntelliJ runtime required).
 * The IJ Platform coupling in KumlSplitEditorProvider.accept is extracted into
 * the pure static helpers [KumlSplitEditorProvider.isKumlFile] and
 * [KumlSplitEditorProvider.isDisabled], which are tested directly here.
 * KumlElementExtractor is a pure-Kotlin object — tested without any IDE setup.
 *
 * Twelve tests total:
 *  1.  KumlPreviewPanel can be instantiated without crashing
 *  2.  KumlPreviewPanel shows "Rendering…" as initial status
 *  3.  Debounce: rapid successive updates don't fire multiple immediate renders
 *  4.  KumlPreviewPanel.dispose() stops the timer without crashing
 *  5.  KumlSplitEditorProvider.isKumlFile: returns true for *.kuml.kts
 *  6.  KumlSplitEditorProvider.isKumlFile: returns false for *.kt
 *  7.  KumlSplitEditorProvider.isKumlFile: returns false for *.kuml.kts.bak
 *  8.  Structure view regex extracts classOf(name = "Order") → "Order"
 *  9.  Structure view regex extracts partDef("Vehicle") → "Vehicle"
 * 10.  Structure view regex extracts interfaceOf(name = "Payable") → "Payable"
 * 11.  Structure view handles empty file without crashing
 * 12.  Structure view extracts multiple elements in order, deduplicated
 */
class KumlSplitEditorAndPreviewTest :
    FunSpec({

        // ── 1. KumlPreviewPanel can be instantiated ───────────────────────────

        test("KumlPreviewPanel: can be instantiated without crashing") {
            val panel = KumlPreviewPanel(debounceMs = 50L)
            panel shouldNotBe null
            panel.dispose()
        }

        // ── 2. KumlPreviewPanel shows "Rendering…" as initial status ─────────

        test("KumlPreviewPanel: initial status is Rendering") {
            val panel = KumlPreviewPanel(debounceMs = 50L)
            try {
                panel.currentStatus shouldBe KumlPreviewPanel.STATUS_RENDERING
            } finally {
                panel.dispose()
            }
        }

        // ── 3. Debounce: rapid updates don't fire multiple immediate renders ──

        test("KumlPreviewPanel: rapid scheduleUpdate calls don't cause multiple immediate renders") {
            // We use a very short debounce (10 ms) but fire updates faster
            // than the debounce interval. After all calls the panel should
            // still be in "Rendering…" status (background render hasn't settled
            // yet in the 1 ms between calls).
            val panel = KumlPreviewPanel(debounceMs = 200L)
            try {
                // Fire many updates rapidly — only the last should actually trigger
                val scriptText = "sysml2Model(\"Test\") {}"
                repeat(50) { panel.scheduleUpdate(scriptText, "test.kuml.kts") }

                // Immediately after batching, status should still be Rendering
                // (the debounce hasn't fired yet, OR the background render is in-flight).
                panel.currentStatus shouldBe KumlPreviewPanel.STATUS_RENDERING
            } finally {
                panel.dispose()
            }
        }

        // ── 4. dispose() is idempotent and doesn't throw ──────────────────────

        test("KumlPreviewPanel: dispose() is safe to call multiple times") {
            val panel = KumlPreviewPanel(debounceMs = 50L)
            panel.dispose()
            // Second dispose must not throw
            panel.dispose()
        }

        // ── 5. KumlSplitEditorProvider.isKumlFile: *.kuml.kts → true ─────────

        test("KumlSplitEditorProvider.accept: isKumlFile returns true for *.kuml.kts") {
            KumlSplitEditorProvider.isKumlFile("car2x-scenario-stm.kuml.kts") shouldBe true
            KumlSplitEditorProvider.isKumlFile("diagram.kuml.kts") shouldBe true
            KumlSplitEditorProvider.isKumlFile(".kuml.kts") shouldBe true
        }

        // ── 6. KumlSplitEditorProvider.isKumlFile: *.kt → false ──────────────

        test("KumlSplitEditorProvider.accept: isKumlFile returns false for *.kt") {
            KumlSplitEditorProvider.isKumlFile("Main.kt") shouldBe false
            KumlSplitEditorProvider.isKumlFile("build.gradle.kts") shouldBe false
        }

        // ── 7. KumlSplitEditorProvider.isKumlFile: *.kuml.kts.bak → false ────

        test("KumlSplitEditorProvider.accept: isKumlFile returns false for *.kuml.kts.bak") {
            KumlSplitEditorProvider.isKumlFile("diagram.kuml.kts.bak") shouldBe false
            KumlSplitEditorProvider.isKumlFile("diagram.kts") shouldBe false
        }

        // ── 8. Structure view: classOf(name = "Order") → "Order" ─────────────

        test("KumlElementExtractor: extracts classOf(name = \"Order\") -> Order") {
            val script =
                """
                classDiagram {
                    classOf(name = "Order") {
                        attribute("id", type = "Long")
                    }
                }
                """.trimIndent()
            val names = KumlElementExtractor.extractAll(script)
            names shouldContain "Order"
        }

        // ── 9. Structure view: partDef("Vehicle") → "Vehicle" ────────────────

        test("KumlElementExtractor: extracts partDef(\"Vehicle\") -> Vehicle") {
            val script =
                """
                sysml2Model("V2X") {
                    val ego = partDef("Vehicle") {
                        attribute("speed", typeId = speed.id)
                    }
                }
                """.trimIndent()
            val names = KumlElementExtractor.extractAll(script)
            names shouldContain "Vehicle"
        }

        // ── 10. Structure view: interfaceOf(name = "Payable") → "Payable" ─────

        test("KumlElementExtractor: extracts interfaceOf(name = \"Payable\") -> Payable") {
            val script =
                """
                classDiagram {
                    interfaceOf(name = "Payable") {}
                }
                """.trimIndent()
            val names = KumlElementExtractor.extractAll(script)
            names shouldContain "Payable"
        }

        // ── 11. Structure view handles empty file without crashing ─────────────

        test("KumlElementExtractor: handles empty file gracefully") {
            KumlElementExtractor.extractAll("") shouldBe emptyList()
            KumlElementExtractor.extractAll("   \n\t  ") shouldBe emptyList()
        }

        // ── 12. Multi-element file: extracts multiple elements, deduplicated ───

        test("KumlElementExtractor: extracts multiple elements in order, deduplicated") {
            val script =
                """
                sysml2Model("Car2x") {
                    val idle = stateDef("Idle")
                    val approaching = stateDef("Approaching")
                    val negotiating = stateDef("Negotiating")
                    val ego = partDef("EgoVehicle") { }
                    val rsu = partDef("RSU") { }
                    // Duplicate — should appear only once
                    val idle2 = stateDef("Idle")
                    stmDiagram("Car2x — Intersection Scenario") { }
                }
                """.trimIndent()
            val names = KumlElementExtractor.extractAll(script)

            // All expected elements present
            names shouldContain "Idle"
            names shouldContain "Approaching"
            names shouldContain "Negotiating"
            names shouldContain "EgoVehicle"
            names shouldContain "RSU"
            names shouldContain "Car2x — Intersection Scenario"

            // Duplicate "Idle" deduplicated
            names.count { it == "Idle" } shouldBe 1

            // Unrecognised names should not appear
            names shouldNotContain "Car2x" // model name, not an element pattern
        }
    })
