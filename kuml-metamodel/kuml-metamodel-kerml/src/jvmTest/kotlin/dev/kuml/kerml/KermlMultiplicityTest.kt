package dev.kuml.kerml

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class KermlMultiplicityTest :
    StringSpec({

        "default is 1..1 (exactly one)" {
            KermlMultiplicity().toSpecForm() shouldBe "1"
            KermlMultiplicity.EXACTLY_ONE.toSpecForm() shouldBe "1"
        }

        "renders SysML 2 concrete syntax" {
            KermlMultiplicity(lower = 0, upper = 1).toSpecForm() shouldBe "0..1"
            KermlMultiplicity(lower = 1, upper = null).toSpecForm() shouldBe "1..*"
            KermlMultiplicity(lower = 0, upper = null).toSpecForm() shouldBe "0..*"
            KermlMultiplicity(lower = 3, upper = 3).toSpecForm() shouldBe "3"
            KermlMultiplicity(lower = 2, upper = 5).toSpecForm() shouldBe "2..5"
        }

        "rejects negative lower bound" {
            shouldThrow<IllegalArgumentException> { KermlMultiplicity(lower = -1, upper = 1) }
        }

        "rejects upper < lower" {
            shouldThrow<IllegalArgumentException> { KermlMultiplicity(lower = 5, upper = 3) }
        }

        "round-trips through kotlinx.serialization" {
            val original = KermlMultiplicity(lower = 1, upper = null)
            val json = Json.encodeToString(original)
            val restored = Json.decodeFromString<KermlMultiplicity>(json)
            restored shouldBe original
        }
    })
