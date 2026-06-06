package dev.kuml.layout.elk

import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutEngineRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ElkLayoutEngineProviderTest :
    FunSpec({

        beforeEach { LayoutEngineRegistry.clear() }
        afterEach { LayoutEngineRegistry.clear() }

        test("provider identifies itself as elk.layered") {
            val provider = ElkLayoutEngineProvider()
            provider.id shouldBe LayoutEngineId("elk.layered")
            provider.engine().id shouldBe LayoutEngineId("elk.layered")
        }

        test("loadFromClasspath discovers the ElkLayoutEngineProvider via META-INF/services") {
            LayoutEngineRegistry.loadFromClasspath()
            val engine = LayoutEngineRegistry.get("elk.layered")
            engine?.shouldBeInstanceOf<ElkLayoutEngine>()
        }
    })
