package dev.kuml.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class RuntimeValuesTest :
    FunSpec({

        test("integral JSON numbers become Long and fractional ones Double") {
            JsonPrimitive(3).toRuntimeValue() shouldBe 3L
            JsonPrimitive(3.5).toRuntimeValue() shouldBe 3.5
            JsonPrimitive(3.0).toRuntimeValue() shouldBe 3.0
        }

        test("booleans strings and null map to their Kotlin counterparts") {
            JsonPrimitive(true).toRuntimeValue() shouldBe true
            JsonPrimitive("abc").toRuntimeValue() shouldBe "abc"
            JsonNull.toRuntimeValue() shouldBe null
        }

        test("a quoted number stays a String") {
            JsonPrimitive("42").toRuntimeValue() shouldBe "42"
        }

        test("objects and arrays round trip through the runtime value form") {
            val json =
                JsonObject(
                    mapOf(
                        "a" to JsonPrimitive(1),
                        "b" to JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive(false))),
                    ),
                )
            runtimeValueToJson(json.toRuntimeValue()) shouldBe json
        }

        test("runtimeValueToJson maps scalars") {
            runtimeValueToJson(null) shouldBe JsonNull
            runtimeValueToJson(7L) shouldBe JsonPrimitive(7L)
            runtimeValueToJson("s") shouldBe JsonPrimitive("s")
            runtimeValueToJson(true) shouldBe JsonPrimitive(true)
        }
    })
