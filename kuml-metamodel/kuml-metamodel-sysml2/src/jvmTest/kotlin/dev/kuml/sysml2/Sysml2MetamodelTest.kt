package dev.kuml.sysml2

import dev.kuml.kerml.KermlElement
import dev.kuml.kerml.KermlMultiplicity
import dev.kuml.kerml.KermlType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class Sysml2MetamodelTest :
    StringSpec({

        "PartDefinition is a SysML 2 element AND a KerML type — preserves layering" {
            val pd: Sysml2Definition = PartDefinition(id = "Vehicle", name = "Vehicle")
            pd.shouldBeInstanceOf<Sysml2Element>()
            pd.shouldBeInstanceOf<KermlType>()
            pd.shouldBeInstanceOf<KermlElement>()
            // Equally fine to downcast through the KerML lens — that's the whole
            // point of the layering, so layout/diff tooling can stay KerML-generic.
            val asKermlType: KermlType = pd
            asKermlType.features shouldBe emptyList()
        }

        "AttributeDefinition and PartDefinition are distinct branches of the sealed root" {
            val parts: Sysml2Definition = PartDefinition(id = "P", name = "P")
            val attrs: Sysml2Definition = AttributeDefinition(id = "A", name = "A")
            (parts is PartDefinition) shouldBe true
            (attrs is AttributeDefinition) shouldBe true
            (parts is AttributeDefinition) shouldBe false
        }

        "PartUsage carries multiplicity and definitionId" {
            val pu =
                PartUsage(
                    id = "Vehicle::engine",
                    name = "engine",
                    qualifiedName = "Vehicle::engine",
                    definitionId = "Engine",
                    multiplicity = KermlMultiplicity.ONE_OR_MORE,
                )
            pu.definitionId shouldBe "Engine"
            pu.multiplicity.toSpecForm() shouldBe "1..*"
        }

        "ConnectionUsage records both endpoints" {
            val cu =
                ConnectionUsage(
                    id = "Vehicle::powerLink",
                    name = "powerLink",
                    qualifiedName = "Vehicle::powerLink",
                    definitionId = "PowerLink",
                    sourceEndId = "Vehicle::engine::outlet",
                    targetEndId = "Vehicle::battery::inlet",
                )
            cu.sourceEndId shouldBe "Vehicle::engine::outlet"
            cu.targetEndId shouldBe "Vehicle::battery::inlet"
        }

        "Sysml2Model.elementById resolves definitions" {
            val vehicle = PartDefinition(id = "Vehicle", name = "Vehicle")
            val engine = PartDefinition(id = "Engine", name = "Engine")
            val model = Sysml2Model(name = "Demo", definitions = listOf(vehicle, engine))
            model.elementById("Vehicle") shouldBe vehicle
            model.elementById("Engine") shouldBe engine
            model.elementById("Nope") shouldBe null
        }

        "BdDiagram is a Sysml2Diagram with an element list" {
            val bdd: Sysml2Diagram =
                BdDiagram(
                    name = "Structural overview",
                    elementIds = listOf("Vehicle", "Engine"),
                )
            bdd.shouldBeInstanceOf<BdDiagram>()
            bdd.elementIds shouldHaveSize 2
        }

        "SysML 2 part def can specialise another via KerML inheritance" {
            val vehicle = PartDefinition(id = "Vehicle", name = "Vehicle")
            val hybrid =
                PartDefinition(
                    id = "HybridVehicle",
                    name = "HybridVehicle",
                    specializations =
                        listOf(
                            dev.kuml.kerml.KermlSpecialization(specificId = "HybridVehicle", generalId = "Vehicle"),
                        ),
                )
            // PartDefinition is its own KerML-layered type — it implements
            // [KermlType] directly rather than extending the concrete
            // [KermlClassifier] data class. (KermlClassifier is the
            // KerML-only variant; SysML 2 has its own concrete types that
            // mirror the same shape via the [KermlType] interface.)
            val vehicleAsKermlType: KermlType = vehicle
            vehicleAsKermlType.id shouldBe "Vehicle"
            hybrid.specializations.first().generalId shouldBe vehicle.id
        }
    })
