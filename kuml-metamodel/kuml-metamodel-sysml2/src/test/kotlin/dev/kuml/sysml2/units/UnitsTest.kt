package dev.kuml.sysml2.units

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class UnitsTest :
    StringSpec({

        "MVP unit catalogue covers automotive showcase needs" {
            Unit.ALL.map { it.symbol } shouldBe
                listOf(
                    "kg",
                    "m",
                    "s",
                    "km/h",
                    "kW",
                    "kWh",
                    "V",
                    "A",
                    "deg",
                )
        }

        "UnitValue renders SysML 2 concrete syntax with brackets" {
            UnitValue(1500.0, Unit.KILOGRAM).toSpecForm() shouldBe "1500.0[kg]"
            UnitValue(120.0, Unit.KMPH).toSpecForm() shouldBe "120.0[km/h]"
        }

        "Number-extension sugar turns 1500.kg into a UnitValue" {
            val mass = 1500.kg
            mass.value shouldBe 1500.0
            mass.unit.symbol shouldBe "kg"
            // Int -> Double widening
            val power = 100.kW
            power.value shouldBe 100.0
            power.unit shouldBe Unit.KILOWATT
        }

        "all sugar extensions resolve to the expected unit" {
            1.0.kg.unit shouldBe Unit.KILOGRAM
            1.0.m.unit shouldBe Unit.METRE
            1.0.s.unit shouldBe Unit.SECOND
            1.0.kmph.unit shouldBe Unit.KMPH
            1.0.kW.unit shouldBe Unit.KILOWATT
            1.0.kWh.unit shouldBe Unit.KILOWATT_HOUR
            1.0.V.unit shouldBe Unit.VOLT
            1.0.A.unit shouldBe Unit.AMPERE
            1.0.deg.unit shouldBe Unit.DEGREE
        }

        "UnitValue round-trips through kotlinx.serialization" {
            val original = 1500.kg
            val json = Json.encodeToString(original)
            val restored = Json.decodeFromString<UnitValue>(json)
            restored shouldBe original
        }

        "toString equals toSpecForm for ergonomic debug output" {
            (60.kWh).toString() shouldBe "60.0[kWh]"
        }
    })
