package dev.kuml.sysml2.units

import kotlinx.serialization.Serializable

/**
 * Typed SI / engineering unit for attribute values — the V2.0.3 MVP slice
 * of the SysML 2 ISQ ("International System of Quantities") library.
 *
 * Concrete syntax in the DSL: `1500[kg]`, `120[km / h]`, `100[kW]`,
 * `60[kWh]`. The brackets are SysML 2 concrete syntax for unit
 * annotations; we mirror them via the [UnitValue.toSpecForm] form so
 * round-trips through the model serialisation stay readable.
 *
 * **MVP scope**: a curated short list of automotive-relevant units —
 * exactly the set the V2.0-roadmap calls out for the `example-automotive`
 * showcase. The full ISQ catalogue (and unit composition, e.g. derive
 * `N·m` from `kg`/`m`/`s²`) is V2.x.
 */
@Serializable
data class Unit(
    /** SysML 2 unit symbol — `"kg"`, `"m"`, `"km/h"`, `"kW"`, … */
    val symbol: String,
    /** Spelled-out unit name — `"kilogram"`, `"metre"`, `"kilowatt"`. */
    val longName: String,
    /** Physical quantity family — `"Mass"`, `"Length"`, `"Power"`, … */
    val quantityKind: String,
) {
    override fun toString(): String = symbol

    companion object {
        /** Mass — kg, the SI base unit. */
        val KILOGRAM: Unit = Unit("kg", "kilogram", "Mass")

        /** Length — m. */
        val METRE: Unit = Unit("m", "metre", "Length")

        /** Time — s. */
        val SECOND: Unit = Unit("s", "second", "Time")

        /** Speed — km/h, the automotive-friendly form (m/s is V2.x). */
        val KMPH: Unit = Unit("km/h", "kilometres per hour", "Speed")

        /** Power — kW. */
        val KILOWATT: Unit = Unit("kW", "kilowatt", "Power")

        /** Energy — kWh, the battery-pack-friendly unit. */
        val KILOWATT_HOUR: Unit = Unit("kWh", "kilowatt-hour", "Energy")

        /** Electric potential — V. */
        val VOLT: Unit = Unit("V", "volt", "Voltage")

        /** Electric current — A. */
        val AMPERE: Unit = Unit("A", "ampere", "Current")

        /** Plane angle — deg (rad as a peer in V2.x). */
        val DEGREE: Unit = Unit("deg", "degree", "Angle")

        /** All MVP units, in a curated order — handy for tests and tab completion. */
        val ALL: List<Unit> =
            listOf(
                KILOGRAM,
                METRE,
                SECOND,
                KMPH,
                KILOWATT,
                KILOWATT_HOUR,
                VOLT,
                AMPERE,
                DEGREE,
            )
    }
}

/**
 * A typed numeric literal with an attached [Unit] — i.e. the value-side of
 * a SysML 2 attribute default like `mass = 1500[kg]`. Round-trips through
 * [toSpecForm] for serialisation parity with SysML 2 textual notation.
 */
@Serializable
data class UnitValue(
    val value: Double,
    val unit: Unit,
) {
    /** SysML 2 concrete-syntax form, e.g. `1500.0[kg]`. */
    fun toSpecForm(): String = "$value[${unit.symbol}]"

    override fun toString(): String = toSpecForm()
}

/**
 * Sugar: `1500.kg` / `120.kmph` / `100.kW` — turns a numeric literal into a
 * [UnitValue] without the user having to type `UnitValue(1500.0, Unit.KILOGRAM)`.
 *
 * The naming mirrors the unit's identifier so IDE completion (`1500.<TAB>`)
 * surfaces the available units alphabetically. Helpers are extensions on
 * `Number` so both `Int` and `Double` literals work.
 */
val Number.kg: UnitValue get() = UnitValue(toDouble(), Unit.KILOGRAM)
val Number.m: UnitValue get() = UnitValue(toDouble(), Unit.METRE)
val Number.s: UnitValue get() = UnitValue(toDouble(), Unit.SECOND)
val Number.kmph: UnitValue get() = UnitValue(toDouble(), Unit.KMPH)
val Number.kW: UnitValue get() = UnitValue(toDouble(), Unit.KILOWATT)
val Number.kWh: UnitValue get() = UnitValue(toDouble(), Unit.KILOWATT_HOUR)
val Number.V: UnitValue get() = UnitValue(toDouble(), Unit.VOLT)
val Number.A: UnitValue get() = UnitValue(toDouble(), Unit.AMPERE)
val Number.deg: UnitValue get() = UnitValue(toDouble(), Unit.DEGREE)
