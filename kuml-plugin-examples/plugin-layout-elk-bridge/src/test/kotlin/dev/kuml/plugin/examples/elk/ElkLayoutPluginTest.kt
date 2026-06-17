package dev.kuml.plugin.examples.elk

import dev.kuml.plugin.api.core.PluginCapability
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ElkLayoutPluginTest :
    FunSpec({

        val plugin = ElkLayoutPlugin()

        test("engines() returns at least one KumlLayoutEngine") {
            plugin.engines().shouldNotBeEmpty()
        }

        test("descriptor id is dev.kuml.plugin.layout.elk") {
            plugin.descriptor.id shouldBe "dev.kuml.plugin.layout.elk"
        }

        test("descriptor capabilities contains LAYOUT") {
            plugin.descriptor.capabilities shouldContain PluginCapability.LAYOUT
        }

        test("descriptor requiredPermissions is empty — layout is pure computation") {
            plugin.descriptor.requiredPermissions shouldBe emptySet()
        }

        test("engines() returns ELK layered engine with id 'elk.layered'") {
            val engines = plugin.engines()
            val ids = engines.map { it.id.value }
            ids shouldContain "elk.layered"
        }

        test("each returned engine has a non-null capabilities field") {
            for (engine in plugin.engines()) {
                engine.capabilities shouldNotBe null
            }
        }

        test("elk engine capabilities.deterministic is documented (false for ELK layered)") {
            val engine = plugin.engines().first { it.id.value == "elk.layered" }
            engine.capabilities.deterministic shouldBe false
        }

        test("plugin implements KumlLayoutPlugin interface") {
            (plugin is dev.kuml.plugin.api.layout.KumlLayoutPlugin) shouldBe true
        }

        test("descriptor kumlVersionRange contains version 0.12.0") {
            val range = plugin.descriptor.kumlVersionRange
            range.contains(
                dev.kuml.plugin.api.core
                    .PluginVersion(0, 12, 0),
            ) shouldBe true
        }
    })
