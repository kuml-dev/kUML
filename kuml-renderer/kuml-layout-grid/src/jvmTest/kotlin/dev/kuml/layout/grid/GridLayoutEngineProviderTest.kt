package dev.kuml.layout.grid

import dev.kuml.layout.DiagramKind
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutEngineRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class GridLayoutEngineProviderTest :
    FunSpec({

        beforeEach { LayoutEngineRegistry.clear() }
        afterEach { LayoutEngineRegistry.clear() }

        test("provider identifies itself as kuml.grid") {
            val provider = GridLayoutEngineProvider()
            provider.id shouldBe LayoutEngineId("kuml.grid")
            provider.engine().id shouldBe LayoutEngineId("kuml.grid")
        }

        test("provider hands back a real GridLayoutEngine instance") {
            GridLayoutEngineProvider().engine().shouldBeInstanceOf<GridLayoutEngine>()
        }

        test("loadFromClasspath discovers the GridLayoutEngineProvider via META-INF/services") {
            LayoutEngineRegistry.loadFromClasspath()
            val engine = LayoutEngineRegistry.get("kuml.grid")
            engine?.shouldBeInstanceOf<GridLayoutEngine>()
        }

        test("pickFor routes a UmlClass diagram to the Grid engine when it's the only one registered") {
            LayoutEngineRegistry.loadFromClasspath()
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlClass)
            engine?.id shouldBe LayoutEngineId("kuml.grid")
        }
    })
