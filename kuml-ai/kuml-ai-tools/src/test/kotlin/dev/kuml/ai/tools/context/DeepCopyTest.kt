package dev.kuml.ai.tools.context

import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class DeepCopyTest :
    FunSpec({

        test("JSON roundtrip clone yields an equal but identity-distinct UmlModel") {
            val original = AnyKumlModel.emptyUml("TestModel")
            val copy = DeepCopy.copy(original)
            copy shouldBe original
            copy shouldNotBe System.identityHashCode(original)
        }

        test("JSON roundtrip clone preserves nested Diagram contents") {
            val uml =
                AnyKumlModel.Uml(
                    name = "DiagramTest",
                    elements =
                        listOf(
                            UmlClass(id = "c1", name = "OrderService"),
                            UmlClass(id = "c2", name = "Customer"),
                        ),
                )
            val copy = DeepCopy.copy(uml) as AnyKumlModel.Uml
            copy.elements.size shouldBe 2
            copy.elements[0].name shouldBe "OrderService"
            copy.elements[1].name shouldBe "Customer"
        }

        test("JSON roundtrip clone of Sysml2Model with definitions round-trips") {
            val original =
                AnyKumlModel.Sysml2(
                    model =
                        Sysml2Model(
                            name = "HybridVehicle",
                            definitions =
                                listOf(
                                    PartDefinition(id = "p1", name = "PowerTrain"),
                                    PartDefinition(id = "p2", name = "Battery"),
                                ),
                        ),
                )
            val copy = DeepCopy.copy(original) as AnyKumlModel.Sysml2
            copy.model.name shouldBe "HybridVehicle"
            copy.model.definitions.size shouldBe 2
        }
    })
