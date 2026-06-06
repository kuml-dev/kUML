package dev.kuml.kerml

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class KermlTypeTest :
    StringSpec({

        "classifier can own features and specialisations" {
            val cls =
                KermlClassifier(
                    id = "Vehicle",
                    name = "Vehicle",
                    features =
                        listOf(
                            KermlFeature(id = "Vehicle::mass", name = "mass", typeId = "Mass"),
                        ),
                    specializations = listOf(KermlSpecialization(specificId = "HybridVehicle", generalId = "Vehicle")),
                )
            cls.features shouldBe
                listOf(
                    KermlFeature(id = "Vehicle::mass", name = "mass", typeId = "Mass"),
                )
            cls.specializations.first().generalId shouldBe "Vehicle"
        }

        "classifier is a KermlType (cross-module marker reach)" {
            val cls: KermlType = KermlClassifier(id = "A", name = "A")
            cls.shouldBeInstanceOf<KermlType>()
        }

        "data type is a separate KermlType branch" {
            val dt: KermlType = KermlDataType(id = "Real", name = "Real")
            dt.shouldBeInstanceOf<KermlDataType>()
            // Not a classifier — that's the structural-vs-value split.
            (dt is KermlClassifier) shouldBe false
        }

        "feature default multiplicity is 1..1" {
            val f = KermlFeature(id = "f", name = "f", typeId = "Real")
            f.multiplicity shouldBe KermlMultiplicity.EXACTLY_ONE
        }
    })
