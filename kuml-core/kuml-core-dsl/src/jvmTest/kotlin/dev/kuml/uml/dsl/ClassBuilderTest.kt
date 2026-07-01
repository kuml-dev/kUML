package dev.kuml.uml.dsl

import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class ClassBuilderTest :
    FunSpec(body = {

        // ── Basic construction ─────────────────────────────────────────────────────

        test(name = "classOf with name builds a UmlClass") {
            val cls =
                umlModel(name = "M") { classOf(name = "Order") }
                    .elements
                    .filterIsInstance<UmlClass>()
                    .first()
            cls.name shouldBe "Order"
        }

        test(name = "classOf id defaults to name at root level") {
            val cls =
                umlModel(name = "M") { classOf(name = "Order") }
                    .elements
                    .filterIsInstance<UmlClass>()
                    .first()
            cls.id shouldBe "Order"
        }

        test(name = "classOf explicit id overrides derived id") {
            val cls =
                umlModel(name = "M") { classOf(name = "Order", id = "custom::Order") }
                    .elements
                    .filterIsInstance<UmlClass>()
                    .first()
            cls.id shouldBe "custom::Order"
        }

        test(name = "classOf default visibility is PUBLIC") {
            val cls =
                umlModel(name = "M") { classOf(name = "Order") }
                    .elements
                    .filterIsInstance<UmlClass>()
                    .first()
            cls.visibility shouldBe Visibility.PUBLIC
        }

        test(name = "classOf isAbstract defaults to false") {
            val cls =
                umlModel(name = "M") { classOf(name = "Order") }
                    .elements
                    .filterIsInstance<UmlClass>()
                    .first()
            cls.isAbstract shouldBe false
        }

        test(name = "classOf sets isAbstract when configured") {
            val cls =
                umlModel(name = "M") { classOf(name = "Shape") { isAbstract = true } }
                    .elements
                    .filterIsInstance<UmlClass>()
                    .first()
            cls.isAbstract shouldBe true
        }

        test(name = "classOf sets visibility when configured") {
            val cls =
                umlModel(name = "M") { classOf(name = "Internal") { visibility = Visibility.PACKAGE } }
                    .elements
                    .filterIsInstance<UmlClass>()
                    .first()
            cls.visibility shouldBe Visibility.PACKAGE
        }

        test(name = "classOf returned handle has correct id") {
            var handle: UmlClass? = null
            umlModel(name = "M") { handle = classOf(name = "Order") }
            handle!!.id shouldBe "Order"
        }

        // ── Attributes ─────────────────────────────────────────────────────────────

        test(name = "attribute with string type is added to class") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") {
                        attribute(name = "id", type = "UUID")
                    }
                }.elements.filterIsInstance<UmlClass>().first()
            cls.attributes shouldHaveSize 1
            cls.attributes[0].name shouldBe "id"
        }

        test(name = "attribute id is derived from class id and attribute name") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") { attribute(name = "id", type = "UUID") }
                }.elements.filterIsInstance<UmlClass>().first()
            cls.attributes[0].id shouldBe "Order::id"
        }

        test(name = "attribute default visibility is PRIVATE") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") { attribute(name = "id", type = "UUID") }
                }.elements.filterIsInstance<UmlClass>().first()
            cls.attributes[0].visibility shouldBe Visibility.PRIVATE
        }

        test(name = "attribute with explicit visibility respects it") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") {
                        attribute(name = "name", type = "String", visibility = Visibility.PROTECTED)
                    }
                }.elements.filterIsInstance<UmlClass>().first()
            cls.attributes[0].visibility shouldBe Visibility.PROTECTED
        }

        test(name = "attribute with isStatic flag is stored") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Counter") { attribute(name = "count", type = "Int", isStatic = true) }
                }.elements.filterIsInstance<UmlClass>().first()
            cls.attributes[0].isStatic shouldBe true
        }

        test(name = "attribute with isReadOnly flag is stored") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Config") { attribute(name = "host", type = "String", isReadOnly = true) }
                }.elements.filterIsInstance<UmlClass>().first()
            cls.attributes[0].isReadOnly shouldBe true
        }

        test(name = "attribute with classifier handle sets referencedId") {
            val model =
                umlModel(name = "M") {
                    val status = enumOf(name = "Status") { literal(name = "ACTIVE") }
                    classOf(name = "Order") { attribute(name = "status", type = status) }
                }
            val cls = model.elements.filterIsInstance<UmlClass>().first()
            cls.attributes[0].type.name shouldBe "Status"
            cls.attributes[0].type.referencedId shouldBe "Status"
        }

        test(name = "multiple attributes accumulate in order") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") {
                        attribute(name = "id", type = "UUID")
                        attribute(name = "createdAt", type = "Instant")
                        attribute(name = "status", type = "String")
                    }
                }.elements.filterIsInstance<UmlClass>().first()
            cls.attributes shouldHaveSize 3
            cls.attributes.map { it.name } shouldBe listOf("id", "createdAt", "status")
        }

        // ── Operations ─────────────────────────────────────────────────────────────

        test(name = "operation is added to class") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") { operation(name = "confirm") }
                }.elements.filterIsInstance<UmlClass>().first()
            cls.operations shouldHaveSize 1
            cls.operations[0].name shouldBe "confirm"
        }

        test(name = "operation id uses parentheses notation") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") { operation(name = "confirm") }
                }.elements.filterIsInstance<UmlClass>().first()
            cls.operations[0].id shouldBe "Order::confirm()"
        }

        test(name = "operation without parameters has empty parameter list") {
            val cls =
                umlModel(name = "M") {
                    classOf(name = "Order") { operation(name = "confirm") }
                }.elements.filterIsInstance<UmlClass>().first()
            cls.operations[0].parameters.shouldBeEmpty()
        }

        // ── Inline extends / implements ────────────────────────────────────────────

        test(name = "extends inside classOf creates a UmlGeneralization in the diagram") {
            val model =
                umlModel(name = "M") {
                    val animal = classOf(name = "Animal")
                    classOf(name = "Dog") { extends(general = animal) }
                }
            val gens = model.elements.filterIsInstance<UmlGeneralization>()
            gens shouldHaveSize 1
            gens[0].specificId shouldBe "Dog"
            gens[0].generalId shouldBe "Animal"
        }

        test(name = "extends by string id creates a UmlGeneralization") {
            val model =
                umlModel(name = "M") {
                    classOf(name = "Dog") { extends(generalId = "Animal") }
                }
            model.elements.filterIsInstance<UmlGeneralization>() shouldHaveSize 1
        }

        test(name = "implements inside classOf creates a UmlInterfaceRealization in the diagram") {
            val model =
                umlModel(name = "M") {
                    val iface = interfaceOf(name = "Serializable")
                    classOf(name = "Order") { implements(iface = iface) }
                }
            val reals = model.elements.filterIsInstance<UmlInterfaceRealization>()
            reals shouldHaveSize 1
            reals[0].implementingId shouldBe "Order"
            reals[0].interfaceId shouldBe "Serializable"
        }
    })

// Helper to extract elements from the KumlDiagram root
private val dev.kuml.core.model.KumlModel.elements
    get() = (root as dev.kuml.core.model.KumlDiagram).elements
