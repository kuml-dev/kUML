package dev.kuml.uml

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

class MultiplicityExplicitTest :
    FunSpec(body = {
        test("an implicit 1 draws no label, an explicit 1 draws \"1\"") {
            multiplicityLabel(multiplicity = Multiplicity(lower = 1, upper = 1), explicit = false) shouldBe null
            multiplicityLabel(multiplicity = Multiplicity(lower = 1, upper = 1), explicit = true) shouldBe "1"
        }

        test("other multiplicities are unchanged by the explicit flag") {
            for (explicit in listOf(false, true)) {
                multiplicityLabel(multiplicity = Multiplicity(lower = 0, upper = null), explicit = explicit) shouldBe "0..*"
                multiplicityLabel(multiplicity = Multiplicity(lower = 1, upper = null), explicit = explicit) shouldBe "1..*"
                multiplicityLabel(multiplicity = Multiplicity(lower = 0, upper = 1), explicit = explicit) shouldBe "0..1"
                multiplicityLabel(multiplicity = Multiplicity(lower = 2, upper = 2), explicit = explicit) shouldBe "2"
            }
        }

        test("UmlAssociationEnd.multiplicityLabel honours the flag") {
            UmlAssociationEnd(typeId = "A").multiplicityLabel() shouldBe null
            UmlAssociationEnd(typeId = "A", multiplicityExplicit = true).multiplicityLabel() shouldBe "1"
        }

        test("JSON without the field loads as implicit, the flag survives a round trip, false is omitted") {
            val old = Json.decodeFromString<UmlAssociationEnd>("""{"typeId":"A"}""")
            old.multiplicityExplicit shouldBe false

            val explicit = UmlAssociationEnd(typeId = "A", multiplicityExplicit = true)
            Json.decodeFromString<UmlAssociationEnd>(Json.encodeToString(UmlAssociationEnd.serializer(), explicit)) shouldBe explicit

            Json.encodeToString(UmlAssociationEnd.serializer(), UmlAssociationEnd(typeId = "A")) shouldNotContain "multiplicityExplicit"
        }
    })
