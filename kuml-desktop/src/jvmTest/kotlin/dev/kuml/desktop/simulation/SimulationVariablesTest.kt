package dev.kuml.desktop.simulation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SimulationVariablesTest :
    FunSpec({

        fun number(raw: String) = parseVariableInput(type = VariableType.NUMBER, raw = raw)

        fun invalid(r: VariableInputResult) = r.shouldBeInstanceOf<VariableInputResult.Invalid>().error

        test("variableTypeOf classifies primitives, null and containers") {
            variableTypeOf(JsonPrimitive(true)) shouldBe VariableType.BOOLEAN
            variableTypeOf(JsonPrimitive(3)) shouldBe VariableType.NUMBER
            variableTypeOf(JsonPrimitive(3.5)) shouldBe VariableType.NUMBER
            variableTypeOf(JsonPrimitive("x")) shouldBe VariableType.TEXT
            variableTypeOf(JsonPrimitive("true")) shouldBe VariableType.TEXT
            variableTypeOf(JsonNull) shouldBe VariableType.READ_ONLY
            variableTypeOf(JsonObject(emptyMap())) shouldBe VariableType.READ_ONLY
            variableTypeOf(JsonArray(emptyList())) shouldBe VariableType.READ_ONLY
        }

        test("integers become Long, decimals and exponents Double") {
            number("42") shouldBe VariableInputResult.Valid(JsonPrimitive(42L))
            number("-7") shouldBe VariableInputResult.Valid(JsonPrimitive(-7L))
            number("3.5") shouldBe VariableInputResult.Valid(JsonPrimitive(3.5))
            number("1e3") shouldBe VariableInputResult.Valid(JsonPrimitive(1000.0))
            number("  12 ") shouldBe VariableInputResult.Valid(JsonPrimitive(12L))
        }

        test("a Long overflow is OUT_OF_RANGE and an overflowing double is NOT_FINITE") {
            invalid(number("9223372036854775808")) shouldBe VariableInputError.OUT_OF_RANGE
            invalid(number("1e999")) shouldBe VariableInputError.NOT_FINITE
        }

        test("toDouble-only spellings are rejected") {
            for (bad in listOf("NaN", "Infinity", "-Infinity", "0x1p3", "1.5d", "1.5f", "1_000", "+5", ".5", "5.", "")) {
                val e = invalid(number(bad))
                (e == VariableInputError.NOT_A_NUMBER || e == VariableInputError.EMPTY) shouldBe true
            }
        }

        test("text up to 1000 characters is valid, one more is TOO_LONG") {
            parseVariableInput(type = VariableType.TEXT, raw = "a".repeat(1000)).shouldBeInstanceOf<VariableInputResult.Valid>()
            invalid(parseVariableInput(type = VariableType.TEXT, raw = "a".repeat(1001))) shouldBe VariableInputError.TOO_LONG
            parseVariableInput(type = VariableType.TEXT, raw = "") shouldBe VariableInputResult.Valid(JsonPrimitive(""))
        }

        test("booleans and read-only types") {
            parseVariableInput(type = VariableType.BOOLEAN, raw = "TRUE") shouldBe VariableInputResult.Valid(JsonPrimitive(true))
            invalid(parseVariableInput(type = VariableType.READ_ONLY, raw = "x")) shouldBe VariableInputError.READ_ONLY
        }

        test("validateVariableName covers empty, invalid, duplicate and full") {
            validateVariableName(name = "", existing = emptySet()) shouldBe VariableInputError.EMPTY
            validateVariableName(name = "1x", existing = emptySet()) shouldBe VariableInputError.INVALID_NAME
            validateVariableName(name = "a-b", existing = emptySet()) shouldBe VariableInputError.INVALID_NAME
            validateVariableName(name = "a".repeat(65), existing = emptySet()) shouldBe VariableInputError.INVALID_NAME
            validateVariableName(name = "x", existing = setOf("x")) shouldBe VariableInputError.NAME_EXISTS
            validateVariableName(name = "y", existing = (1..64).map { "v$it" }.toSet()) shouldBe VariableInputError.TOO_MANY
            validateVariableName(name = "_ok1", existing = setOf("x")) shouldBe null
        }

        test("formatVariableValue shows an em dash for null, JSON for values and cuts long text") {
            formatVariableValue(value = null) shouldBe "—"
            formatVariableValue(value = JsonPrimitive("a")) shouldBe "\"a\""
            formatVariableValue(value = JsonPrimitive(5)) shouldBe "5"
            val long = formatVariableValue(value = JsonPrimitive("x".repeat(500)), maxChars = 20)
            long.length shouldBe 20
            long.endsWith("…") shouldBe true
        }

        // ── timeline ─────────────────────────────────────────────────────────

        val v0 = mapOf("a" to JsonPrimitive(0))
        val v1 = mapOf("a" to JsonPrimitive(1))
        val v2 = mapOf("a" to JsonPrimitive(2))

        test("at() before the first checkpoint returns the first one") {
            val t = VariableTimeline(initialTraceSize = 3, initial = v0)
            t.at(0) shouldBe v0
            t.at(3) shouldBe v0
            t.at(99) shouldBe v0
        }

        test("at() picks the latest checkpoint at or before the position") {
            val t = VariableTimeline(initialTraceSize = 3, initial = v0)
            t.record(traceSize = 8, variables = v1)
            t.record(traceSize = 12, variables = v2)
            t.at(5) shouldBe v0
            t.at(8) shouldBe v1
            t.at(11) shouldBe v1
            t.at(12) shouldBe v2
        }

        test("record() with the same trace size replaces the checkpoint") {
            val t = VariableTimeline(initialTraceSize = 3, initial = v0)
            t.record(traceSize = 3, variables = v1)
            t.at(3) shouldBe v1
            t.record(traceSize = 8, variables = v1)
            t.record(traceSize = 8, variables = v2)
            t.at(8) shouldBe v2
        }

        test("truncateAfter drops later checkpoints but always keeps the first") {
            val t = VariableTimeline(initialTraceSize = 3, initial = v0)
            t.record(traceSize = 8, variables = v1)
            t.record(traceSize = 12, variables = v2)
            t.truncateAfter(8)
            t.at(12) shouldBe v1
            t.truncateAfter(0)
            t.at(12) shouldBe v0
        }

        test("reset starts a fresh history") {
            val t = VariableTimeline(initialTraceSize = 3, initial = v0)
            t.record(traceSize = 8, variables = v1)
            t.reset(initialTraceSize = 2, initial = v2)
            t.at(8) shouldBe v2
        }
    })
