package dev.kuml.uml.dsl

import dev.kuml.core.dsl.classDiagram
import dev.kuml.core.dsl.layout.LayoutMetadataKeys
import dev.kuml.core.model.ClassDiagramConfig
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlStateMachine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ClassDiagramBuilderTest :
    FunSpec(body = {

        test(name = "empty class diagram builds without error") {
            val d = classDiagram(name = "Empty") {}
            d.name shouldBe "Empty"
            d.elements.shouldHaveSize(0)
        }

        test(name = "classDiagram contains added class") {
            val d =
                classDiagram(name = "My Diagram") {
                    classOf(name = "Order") {
                        attribute(
                            name = "id",
                            type = "UUID",
                        )
                    }
                }
            val classes = d.elements.filterIsInstance<UmlClass>()
            classes shouldHaveSize 1
            classes.first().name shouldBe "Order"
        }

        test(name = "classDiagram contains interface and generalization") {
            val d =
                classDiagram(name = "Hierarchy") {
                    val animal = classOf(name = "Animal") { isAbstract = true }
                    val dog = classOf(name = "Dog") {}
                    generalization(
                        specific = dog,
                        general = animal,
                    )
                }
            d.elements.filterIsInstance<UmlClass>() shouldHaveSize 2
            val gens = d.elements.filterIsInstance<UmlGeneralization>()
            gens shouldHaveSize 1
            gens.first().specificId shouldBe "Dog"
            gens.first().generalId shouldBe "Animal"
        }

        test(name = "classDiagram with association between two classes") {
            val d =
                classDiagram(name = "Association") {
                    val customer = classOf(name = "Customer") {}
                    val order = classOf(name = "Order") {}
                    association(
                        source = customer,
                        target = order,
                    ) {
                        source { multiplicity(spec = "1") }
                        target { multiplicity(spec = "0..*") }
                    }
                }
            d.elements.filterIsInstance<UmlAssociation>() shouldHaveSize 1
        }

        test(name = "showAttributes false is stored in config") {
            val d =
                classDiagram(name = "Config Test") {
                    showAttributes = false
                }
            val config = d.config
            config.shouldBeInstanceOf<ClassDiagramConfig>()
            config.showAttributes shouldBe false
            config.showOperations shouldBe true
        }

        test(name = "adding state machine to class diagram throws") {
            val sm =
                UmlStateMachine(
                    id = "OrderSM",
                    name = "OrderSM",
                )
            val builder = ClassDiagramBuilder(name = "Bad")
            shouldThrow<IllegalArgumentException> {
                builder.addNamedElement(sm)
            }
        }

        test(name = "adding include relationship to class diagram throws") {
            val include =
                UmlInclude(
                    id = "include::A..>B",
                    baseId = "A",
                    additionId = "B",
                )
            val builder = ClassDiagramBuilder(name = "Bad")
            shouldThrow<IllegalArgumentException> {
                builder.addRelationship(include)
            }
        }

        test(name = "diagram type is CLASS") {
            val d = classDiagram(name = "Test") {}
            d.type shouldBe DiagramType.CLASS
        }

        // ── V2.x — DSL-Opt-in für ELK-Edge-Merging ────────────────────────────

        test(name = "mergeEdges default → kein Metadata-Key gesetzt") {
            val d = classDiagram(name = "NoMerge") {}
            d.metadata shouldNotContainKey LayoutMetadataKeys.MERGE_EDGES
        }

        test(name = "mergeEdges = true → Metadata-Flag(true) in KumlDiagram.metadata") {
            val d =
                classDiagram(name = "WithMerge") {
                    mergeEdges = true
                }
            val flag = d.metadata[LayoutMetadataKeys.MERGE_EDGES]
            flag.shouldBeInstanceOf<KumlMetaValue.Flag>()
            flag.value shouldBe true
        }

        test(name = "mergeEdges = false → expliziter Opt-out wird ebenfalls serialisiert") {
            val d =
                classDiagram(name = "ExplicitOff") {
                    mergeEdges = false
                }
            val flag = d.metadata[LayoutMetadataKeys.MERGE_EDGES]
            flag.shouldBeInstanceOf<KumlMetaValue.Flag>()
            flag.value shouldBe false
        }

        test(name = "mergeEdges koexistiert mit layoutEngine im Metadata-Block") {
            val d =
                classDiagram(name = "Both") {
                    layoutEngine = "elk"
                    mergeEdges = true
                }
            (d.metadata[LayoutMetadataKeys.ENGINE] as? KumlMetaValue.Text)?.value shouldBe "elk"
            (d.metadata[LayoutMetadataKeys.MERGE_EDGES] as? KumlMetaValue.Flag)?.value shouldBe true
        }
    })
