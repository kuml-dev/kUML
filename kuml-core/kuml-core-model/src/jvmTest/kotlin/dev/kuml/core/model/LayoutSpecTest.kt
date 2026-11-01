package dev.kuml.core.model

import dev.kuml.core.model.layout.LayoutFlow
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.core.model.layout.LayoutSpecBuilder
import dev.kuml.core.model.layout.LayoutSpecCollector
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class LayoutSpecTest :
    FunSpec({
        class TestBuilder : LayoutSpecBuilder()

        test("legacy SysML 2 layout JSON (without the new fields) still decodes") {
            val legacy = """{"direction":"Right","wrapColumns":3,"sameLayer":[["A","B"]],"first":["C"]}"""
            val spec = Json.decodeFromString(LayoutSpec.serializer(), legacy)
            spec shouldBe
                LayoutSpec(
                    direction = LayoutFlow.Right,
                    wrapColumns = 3,
                    sameLayer = listOf(listOf("A", "B")),
                    first = listOf("C"),
                )
            spec.maxWidth shouldBe null
        }

        test("round trip with all fields") {
            val spec =
                LayoutSpec(
                    direction = LayoutFlow.Down,
                    wrapColumns = 2,
                    sameLayer = listOf(listOf("a", "b")),
                    first = listOf("a"),
                    last = listOf("z"),
                    maxWidth = 1800,
                    aspectRatio = 1.6,
                )
            val json = Json { encodeDefaults = true }
            json.decodeFromString(LayoutSpec.serializer(), json.encodeToString(LayoutSpec.serializer(), spec)) shouldBe spec
        }

        test("labelMaxWidth: not empty, merged, round trip, absent in default JSON") {
            LayoutSpec(labelMaxWidth = 220).isEmpty shouldBe false
            LayoutSpec(labelMaxWidth = 150).mergedWith(LayoutSpec(maxWidth = 900)).labelMaxWidth shouldBe 150
            LayoutSpec(labelMaxWidth = 150).mergedWith(LayoutSpec(labelMaxWidth = 300)).labelMaxWidth shouldBe 300
            val json = Json { encodeDefaults = false }
            json.encodeToString(LayoutSpec.serializer(), LayoutSpec(maxWidth = 900)) shouldBe """{"maxWidth":900}"""
            val spec = LayoutSpec(labelMaxWidth = 260)
            json.decodeFromString(LayoutSpec.serializer(), json.encodeToString(LayoutSpec.serializer(), spec)) shouldBe spec
            json.decodeFromString(LayoutSpec.serializer(), """{"maxWidth":900}""").labelMaxWidth shouldBe null
        }

        test("showSubjectEdges (wave 2E-a): not empty, merged, round trip, absent in default JSON, legacy JSON decodes") {
            LayoutSpec(showSubjectEdges = true).isEmpty shouldBe false
            LayoutSpec(showSubjectEdges = false).isEmpty shouldBe false
            LayoutSpec(showSubjectEdges = true).mergedWith(LayoutSpec(maxWidth = 900)).showSubjectEdges shouldBe true
            LayoutSpec(showSubjectEdges = true).mergedWith(LayoutSpec(showSubjectEdges = false)).showSubjectEdges shouldBe false
            val json = Json { encodeDefaults = false }
            json.encodeToString(LayoutSpec.serializer(), LayoutSpec(maxWidth = 900)) shouldBe """{"maxWidth":900}"""
            val spec = LayoutSpec(showSubjectEdges = true)
            json.decodeFromString(LayoutSpec.serializer(), json.encodeToString(LayoutSpec.serializer(), spec)) shouldBe spec
            json.decodeFromString(LayoutSpec.serializer(), """{"wrapColumns":2}""").showSubjectEdges shouldBe null
        }

        test("mergedWith: scalars of the later spec win, lists are appended") {
            val a = LayoutSpec(direction = LayoutFlow.Down, wrapColumns = 2, first = listOf("x"))
            val b = LayoutSpec(wrapColumns = 4, first = listOf("y"), maxWidth = 900)
            a.mergedWith(b) shouldBe
                LayoutSpec(direction = LayoutFlow.Down, wrapColumns = 4, first = listOf("x", "y"), maxWidth = 900)
        }

        test("builder accepts id strings, ignores an empty sameLayer, collector merges and drops empties") {
            val c = LayoutSpecCollector()
            c.result() shouldBe null
            val b =
                TestBuilder().apply {
                    sameLayer("A::x", "A::y")
                    sameLayer()
                    first("A::x")
                    aspectRatio = 1.5
                }
            c.add(b.build())
            c.add(TestBuilder().apply { maxWidth = 1200 }.build())
            c.result() shouldBe
                LayoutSpec(
                    sameLayer = listOf(listOf("A::x", "A::y")),
                    first = listOf("A::x"),
                    maxWidth = 1200,
                    aspectRatio = 1.5,
                )
            LayoutSpecCollector().apply { add(TestBuilder().build()) }.result() shouldBe null
        }
    })
