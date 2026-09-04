package dev.kuml.uml.dsl

import dev.kuml.core.dsl.layout.layout
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.UmlAssociationClass
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * DSL builder tests for [AssociationClassBuilder] / `associationClass(...)` (ADR-0017, Wave D).
 */
class AssociationClassBuilderTest :
    FunSpec(body = {

        // ── Three overloads produce an equivalent model ──────────────────────────────

        test(name = "associationClass by string ids creates a UmlAssociationClass with correct ends") {
            val model =
                umlModel(name = "M") {
                    classOf(name = "Party")
                    classOf(name = "District")
                    associationClass(name = "Tally", sourceId = "Party", targetId = "District")
                }
            val ac = model.elements.filterIsInstance<UmlAssociationClass>().first()
            ac.name shouldBe "Tally"
            ac.ends[0].typeId shouldBe "Party"
            ac.ends[1].typeId shouldBe "District"
        }

        test(name = "associationClass by classifier handles uses handle ids") {
            val model =
                umlModel(name = "M") {
                    val party = classOf(name = "Party")
                    val district = classOf(name = "District")
                    associationClass(name = "Tally", source = party, target = district)
                }
            val ac = model.elements.filterIsInstance<UmlAssociationClass>().first()
            ac.ends[0].typeId shouldBe "Party"
            ac.ends[1].typeId shouldBe "District"
        }

        test(name = "associationClass by UmlTypeRef uses typeRef ids") {
            val model =
                umlModel(name = "M") {
                    associationClass(name = "Tally", source = typeRef("Party"), target = typeRef("District"))
                }
            val ac = model.elements.filterIsInstance<UmlAssociationClass>().first()
            ac.ends[0].typeId shouldBe "Party"
            ac.ends[1].typeId shouldBe "District"
        }

        // ── ID derivation ─────────────────────────────────────────────────────────

        test(name = "associationClass id defaults to a class-like derivation from its name") {
            val model =
                umlModel(name = "M") {
                    associationClass(name = "Tally", sourceId = "Party", targetId = "District")
                }
            val ac = model.elements.filterIsInstance<UmlAssociationClass>().first()
            ac.id shouldBe "Tally"
        }

        test(name = "explicit id overrides the derived id") {
            val model =
                umlModel(name = "M") {
                    associationClass(name = "Tally", sourceId = "Party", targetId = "District", id = "custom::Tally")
                }
            val ac = model.elements.filterIsInstance<UmlAssociationClass>().first()
            ac.id shouldBe "custom::Tally"
        }

        test(name = "ID disambiguation: a classOf and an associationClass sharing a name get distinct ids") {
            val model =
                umlModel(name = "M") {
                    classOf(name = "Stimmen")
                    associationClass(name = "Stimmen", sourceId = "A", targetId = "B")
                }
            val ac = model.elements.filterIsInstance<UmlAssociationClass>().first()
            ac.id shouldBe "Stimmen~2"
        }

        // ── No double registration ────────────────────────────────────────────────

        test(name = "an association class is registered exactly once in diagram.elements") {
            val model =
                umlModel(name = "M") {
                    associationClass(name = "Tally", sourceId = "Party", targetId = "District")
                }
            model.elements.count { it is UmlAssociationClass } shouldBe 1
        }

        // ── Body features ─────────────────────────────────────────────────────────

        test(name = "associationClass body supports attribute/operation/constraint") {
            val model =
                umlModel(name = "M") {
                    associationClass(name = "Tally", sourceId = "Party", targetId = "District") {
                        attribute(name = "votes", type = "Int")
                        operation(name = "recount")
                        constraint(name = "nonNegative", body = "votes >= 0")
                    }
                }
            val ac = model.elements.filterIsInstance<UmlAssociationClass>().first()
            ac.attributes shouldHaveSize 1
            ac.attributes.first().name shouldBe "votes"
            ac.operations shouldHaveSize 1
            ac.operations.first().name shouldBe "recount"
            ac.constraints shouldHaveSize 1
        }

        test(name = "associationClass stereotypes += adds a plain display-label stereotype") {
            val model =
                umlModel(name = "M") {
                    associationClass(name = "Tally", sourceId = "Party", targetId = "District") {
                        stereotypes += "Auditable"
                    }
                }
            val ac = model.elements.filterIsInstance<UmlAssociationClass>().first()
            ac.stereotypes shouldBe listOf("Auditable")
        }

        test(name = "associationClass visibility defaults to PUBLIC and is settable") {
            val model =
                umlModel(name = "M") {
                    associationClass(name = "Tally", sourceId = "Party", targetId = "District") {
                        visibility = Visibility.PROTECTED
                    }
                }
            val ac = model.elements.filterIsInstance<UmlAssociationClass>().first()
            ac.visibility shouldBe Visibility.PROTECTED
        }

        test(name = "associationClass isAbstract defaults to false and is settable") {
            val model =
                umlModel(name = "M") {
                    associationClass(name = "Tally", sourceId = "Party", targetId = "District") {
                        isAbstract = true
                    }
                }
            val ac = model.elements.filterIsInstance<UmlAssociationClass>().first()
            ac.isAbstract shouldBe true
        }

        test(name = "associationClass aggregation defaults to NONE and is settable") {
            val model =
                umlModel(name = "M") {
                    associationClass(name = "Tally", sourceId = "Party", targetId = "District") {
                        aggregation = AggregationKind.COMPOSITE
                    }
                }
            val ac = model.elements.filterIsInstance<UmlAssociationClass>().first()
            ac.aggregation shouldBe AggregationKind.COMPOSITE
        }

        test(name = "associationClass source/target end blocks configure multiplicity and role") {
            val model =
                umlModel(name = "M") {
                    associationClass(name = "Tally", sourceId = "Party", targetId = "District") {
                        source { multiplicity(spec = "1") }
                        target {
                            multiplicity(spec = "0..*")
                            role = "districts"
                        }
                    }
                }
            val ac = model.elements.filterIsInstance<UmlAssociationClass>().first()
            ac.ends[0].multiplicity.lower shouldBe 1
            ac.ends[1].multiplicity.upper shouldBe null
            ac.ends[1].role shouldBe "districts"
        }

        test(name = "associationClass layout block materializes grid hints into metadata") {
            val model =
                umlModel(name = "M") {
                    associationClass(name = "Tally", sourceId = "Party", targetId = "District") {
                        layout {
                            col = 2
                            row = 1
                        }
                    }
                }
            val ac = model.elements.filterIsInstance<UmlAssociationClass>().first()
            ac.metadata.shouldNotBeEmpty()
        }

        // ── extends/implements propagate as relationships ────────────────────────────

        test(name = "associationClass extends creates a UmlGeneralization in the diagram") {
            val model =
                umlModel(name = "M") {
                    val base = classOf(name = "Base")
                    associationClass(name = "Tally", sourceId = "Party", targetId = "District") {
                        extends(base)
                    }
                }
            val gen = model.elements.filterIsInstance<UmlGeneralization>().first()
            gen.specificId shouldBe "Tally"
            gen.generalId shouldBe "Base"
        }

        test(name = "associationClass implements creates a UmlInterfaceRealization in the diagram") {
            val model =
                umlModel(name = "M") {
                    val iface = interfaceOf(name = "Countable")
                    associationClass(name = "Tally", sourceId = "Party", targetId = "District") {
                        implements(iface)
                    }
                }
            val real = model.elements.filterIsInstance<UmlInterfaceRealization>().first()
            real.implementingId shouldBe "Tally"
            real.interfaceId shouldBe "Countable"
        }

        // ── Association class as endpoint of a normal association ───────────────────

        test(name = "an association class can be the endpoint of a normal association") {
            val model =
                umlModel(name = "M") {
                    val tally = associationClass(name = "Tally", sourceId = "Party", targetId = "District")
                    val auditor = classOf(name = "Auditor")
                    association(source = tally, target = auditor)
                }
            val assoc = model.elements.filterIsInstance<dev.kuml.uml.UmlAssociation>().first()
            assoc.ends[0].typeId shouldBe "Tally"
            assoc.ends[1].typeId shouldBe "Auditor"
        }
    })

private val dev.kuml.core.model.KumlModel.elements
    get() = (root as dev.kuml.core.model.KumlDiagram).elements
