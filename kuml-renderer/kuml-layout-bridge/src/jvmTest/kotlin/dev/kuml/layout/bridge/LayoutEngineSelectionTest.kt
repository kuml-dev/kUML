package dev.kuml.layout.bridge

import dev.kuml.layout.DiagramKind
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.layout.grid.GridLayoutEngineProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Verifiziert die Engine-Auswahllogik des [LayoutEngineRegistry]:
 *
 * - `elk.layered` ist Default für alle Diagrammtypen (ELK vor Grid registriert)
 * - Grid ist opt-in via expliziter Preference (`--layout=grid`)
 * - Explicit preference überschreibt den Default
 * - Registry-Reihenfolge: ELK vor Grid → ELK gewinnt bei `pickFor(CLASS, null)`
 * - Fallback: `pickFor(DEPLOYMENT, null)` → ELK (Grid hat DEPLOYMENT nicht in
 *   capabilities)
 *
 * Jeder Test arbeitet mit einer frischen, isolierten Registry.
 */
class LayoutEngineSelectionTest :
    FunSpec({

        /**
         * Hilfsfunktion: registriert ELK ZUERST, dann Grid — so wie die
         * Produktions-Pipeline es tut (ELK ist der Default).
         */
        fun setupRegistry() {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            LayoutEngineRegistry.register(GridLayoutEngineProvider())
        }

        afterEach {
            LayoutEngineRegistry.clear()
        }

        test("DiagramKind.UmlClass → picks elk.layered by default (elk registered first)") {
            setupRegistry()
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlClass, null)
            engine shouldNotBe null
            engine!!.id shouldBe LayoutEngineId("elk.layered")
        }

        test("DiagramKind.UmlComponent → picks elk.layered by default") {
            setupRegistry()
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlComponent, null)
            engine shouldNotBe null
            engine!!.id shouldBe LayoutEngineId("elk.layered")
        }

        test("DiagramKind.UmlUseCase → picks elk.layered by default") {
            setupRegistry()
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlUseCase, null)
            engine shouldNotBe null
            engine!!.id shouldBe LayoutEngineId("elk.layered")
        }

        test("DiagramKind.UmlState → picks elk.layered by default") {
            setupRegistry()
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlState, null)
            engine shouldNotBe null
            engine!!.id shouldBe LayoutEngineId("elk.layered")
        }

        test("DiagramKind.UmlClass with explicit grid preference → picks kuml.grid") {
            setupRegistry()
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlClass, LayoutEngineId("kuml.grid"))
            engine shouldNotBe null
            engine!!.id shouldBe LayoutEngineId("kuml.grid")
        }

        test("DiagramKind.UmlClass with explicit elk preference → picks elk.layered") {
            setupRegistry()
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlClass, LayoutEngineId("elk.layered"))
            engine shouldNotBe null
            engine!!.id shouldBe LayoutEngineId("elk.layered")
        }

        test("pickFor(UmlClass, null) with elk registered before grid → elk wins (production default)") {
            LayoutEngineRegistry.clear()
            // Production order: elk first
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            LayoutEngineRegistry.register(GridLayoutEngineProvider())
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlClass, null)
            engine!!.id shouldBe LayoutEngineId("elk.layered")
        }

        test("pickFor(UmlClass, null) with grid registered before elk → grid wins (insertion-order)") {
            LayoutEngineRegistry.clear()
            // Reverse order: grid first — confirms order matters
            LayoutEngineRegistry.register(GridLayoutEngineProvider())
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlClass, null)
            // Both support UmlClass; grid was first so it wins
            engine!!.id shouldBe LayoutEngineId("kuml.grid")
        }

        test("pickFor(Generic, null) → elk wins (elk registered first)") {
            setupRegistry()
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.Generic, null)
            engine shouldNotBe null
            // elk is registered first and supports Generic
            engine!!.id shouldBe LayoutEngineId("elk.layered")
        }
    })
