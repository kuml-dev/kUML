package dev.kuml.layout

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

private class FakeEngine(
    override val id: LayoutEngineId,
    private val kinds: Set<DiagramKind>,
) : KumlLayoutEngine {
    override val capabilities: LayoutCapabilities =
        LayoutCapabilities(
            deterministic = true,
            supportedDiagramKinds = kinds,
            supportedEdgeStyles = setOf(EdgeRouteStyle.Direct),
            respectsGridHints = false,
            respectsRelativeConstraints = false,
            maxRecommendedNodes = 100,
        )

    override fun layout(
        graph: LayoutGraph,
        hints: LayoutHints,
    ): LayoutResult =
        LayoutResult(
            engineId = id,
            seed = hints.deterministicSeed,
            canvas = Size(width = 0f, height = 0f),
            nodes = emptyMap(),
            edges = emptyMap(),
            groups = emptyMap(),
        )
}

private class FakeProvider(
    override val id: LayoutEngineId,
    kinds: Set<DiagramKind>,
) : KumlLayoutEngineProvider {
    private val instance = FakeEngine(id = id, kinds = kinds)

    override fun engine(): KumlLayoutEngine = instance
}

class LayoutEngineRegistryTest :
    FunSpec({

        beforeEach { LayoutEngineRegistry.clear() }
        afterEach { LayoutEngineRegistry.clear() }

        test("registered providers are looked up by id (typed + String overload)") {
            val provider =
                FakeProvider(
                    id = LayoutEngineId("test.fake"),
                    kinds = setOf(DiagramKind.UmlClass, DiagramKind.Generic),
                )
            LayoutEngineRegistry.register(provider)
            LayoutEngineRegistry.get(LayoutEngineId("test.fake"))?.id shouldBe LayoutEngineId("test.fake")
            LayoutEngineRegistry.get("test.fake")?.id shouldBe LayoutEngineId("test.fake")
            LayoutEngineRegistry.get("nope") shouldBe null
        }

        test("ids() returns alphabetically sorted engine ids") {
            LayoutEngineRegistry.register(FakeProvider(id = LayoutEngineId("z.engine"), kinds = setOf(DiagramKind.Generic)))
            LayoutEngineRegistry.register(FakeProvider(id = LayoutEngineId("a.engine"), kinds = setOf(DiagramKind.Generic)))
            LayoutEngineRegistry.register(FakeProvider(id = LayoutEngineId("m.engine"), kinds = setOf(DiagramKind.Generic)))
            LayoutEngineRegistry.ids().map { it.value } shouldBe listOf("a.engine", "m.engine", "z.engine")
        }

        test("pickFor — explicit preference wins over capability match") {
            val a = FakeProvider(id = LayoutEngineId("a"), kinds = setOf(DiagramKind.UmlClass))
            val b = FakeProvider(id = LayoutEngineId("b"), kinds = setOf(DiagramKind.UmlClass))
            LayoutEngineRegistry.register(a)
            LayoutEngineRegistry.register(b)

            val picked = LayoutEngineRegistry.pickFor(kind = DiagramKind.UmlClass, preferredEngineId = LayoutEngineId("b"))
            picked?.id shouldBe LayoutEngineId("b")
        }

        test("pickFor — falls back to capability match when preferred id is unknown") {
            LayoutEngineRegistry.register(
                FakeProvider(id = LayoutEngineId("a"), kinds = setOf(DiagramKind.UmlClass)),
            )
            val picked = LayoutEngineRegistry.pickFor(kind = DiagramKind.UmlClass, preferredEngineId = LayoutEngineId("nope"))
            picked?.id shouldBe LayoutEngineId("a")
        }

        test("pickFor — picks a capability-matching engine when no preference is given") {
            LayoutEngineRegistry.register(FakeProvider(id = LayoutEngineId("a"), kinds = setOf(DiagramKind.UmlState)))
            LayoutEngineRegistry.register(FakeProvider(id = LayoutEngineId("b"), kinds = setOf(DiagramKind.UmlClass)))
            val picked = LayoutEngineRegistry.pickFor(kind = DiagramKind.UmlClass)
            picked?.id shouldBe LayoutEngineId("b")
        }

        test("pickFor — falls back to a Generic-capable engine when no diagram-specific match exists") {
            LayoutEngineRegistry.register(FakeProvider(id = LayoutEngineId("a"), kinds = setOf(DiagramKind.UmlSequence)))
            LayoutEngineRegistry.register(FakeProvider(id = LayoutEngineId("generic"), kinds = setOf(DiagramKind.Generic)))
            val picked = LayoutEngineRegistry.pickFor(kind = DiagramKind.UmlClass)
            picked?.id shouldBe LayoutEngineId("generic")
        }

        test("pickFor — returns null when registry is empty") {
            LayoutEngineRegistry.pickFor(kind = DiagramKind.UmlClass) shouldBe null
        }

        test("loadFromClasspath discovers ServiceLoader-registered providers") {
            // Im Test-Classpath sind die echten Provider von kuml-layout-grid und
            // kuml-layout-elk noch nicht sichtbar (kuml-layout-api hat keine
            // testRuntime-Abhängigkeit auf sie). Daher prüfen wir nur, dass die
            // Methode nicht crasht und keine Provider entdeckt — die
            // Discovery-Integration über die Engine-Module wird in
            // GridLayoutEngineProviderTest / ElkLayoutEngineProviderTest geprüft.
            LayoutEngineRegistry.loadFromClasspath()
            LayoutEngineRegistry.ids() shouldBe emptyList()
        }

        test("clear empties the registry") {
            LayoutEngineRegistry.register(FakeProvider(id = LayoutEngineId("a"), kinds = setOf(DiagramKind.Generic)))
            LayoutEngineRegistry.ids() shouldContain LayoutEngineId("a")
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.ids() shouldBe emptyList()
        }
    })
