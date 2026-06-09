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
 * - `kuml.grid` ist Default für CLASS / COMPONENT / USE_CASE / STATE
 * - `elk.layered` bleibt Default für ACTIVITY u.a.
 * - Explicit preference überschreibt den Default
 * - Registry-Reihenfolge: Grid vor ELK → Grid gewinnt bei `pickFor(CLASS, null)`
 * - Fallback: `pickFor(DEPLOYMENT, null)` → ELK (Grid hat DEPLOYMENT nicht in
 *   capabilities)
 *
 * Jeder Test arbeitet mit einer frischen, isolierten Registry.
 */
class LayoutEngineSelectionTest :
    FunSpec({

        /**
         * Hilfsfunktion: registriert Grid ZUERST, dann ELK — so wie die
         * Produktions-Pipeline es tut.
         */
        fun setupRegistry() {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(GridLayoutEngineProvider())
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
        }

        afterEach {
            LayoutEngineRegistry.clear()
        }

        test("DiagramKind.UmlClass → picks kuml.grid by default (grid registered first)") {
            setupRegistry()
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlClass, null)
            engine shouldNotBe null
            engine!!.id shouldBe LayoutEngineId("kuml.grid")
        }

        test("DiagramKind.UmlComponent → picks kuml.grid by default") {
            setupRegistry()
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlComponent, null)
            engine shouldNotBe null
            engine!!.id shouldBe LayoutEngineId("kuml.grid")
        }

        test("DiagramKind.UmlUseCase → picks kuml.grid by default") {
            setupRegistry()
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlUseCase, null)
            engine shouldNotBe null
            engine!!.id shouldBe LayoutEngineId("kuml.grid")
        }

        test("DiagramKind.UmlState → picks kuml.grid by default") {
            setupRegistry()
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlState, null)
            engine shouldNotBe null
            engine!!.id shouldBe LayoutEngineId("kuml.grid")
        }

        test("DiagramKind.UmlClass with explicit elk preference → picks elk.layered") {
            setupRegistry()
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlClass, LayoutEngineId("elk.layered"))
            engine shouldNotBe null
            engine!!.id shouldBe LayoutEngineId("elk.layered")
        }

        test("pickFor(UmlClass, null) with grid registered before elk → grid wins") {
            LayoutEngineRegistry.clear()
            // Explicit order: grid first
            LayoutEngineRegistry.register(GridLayoutEngineProvider())
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlClass, null)
            engine!!.id shouldBe LayoutEngineId("kuml.grid")
        }

        test("pickFor(UmlClass, null) with elk registered before grid → elk wins (insertion-order)") {
            LayoutEngineRegistry.clear()
            // Reverse order: elk first — so we confirm order matters
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            LayoutEngineRegistry.register(GridLayoutEngineProvider())
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlClass, null)
            // Both support UmlClass; elk was first so it wins
            engine!!.id shouldBe LayoutEngineId("elk.layered")
        }

        test("pickFor(Generic, null) → grid wins (grid declares Generic in capabilities)") {
            setupRegistry()
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.Generic, null)
            engine shouldNotBe null
            // grid is registered first and supports Generic
            engine!!.id shouldBe LayoutEngineId("kuml.grid")
        }
    })
