package dev.kuml.io.emf

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class RoundtripFullTest :
    FunSpec({

        val toEmf = UmlToEmfConverter()
        val toUml = EmfToUmlConverter()

        beforeSpec { EmfBootstrap.init() }

        fun kumlModel(vararg elements: dev.kuml.core.model.KumlElement): KumlModel =
            KumlModel(
                root =
                    KumlDiagram(
                        id = "roundtrip-diagram",
                        name = "Roundtrip Diagram",
                        type = DiagramType.CLASS,
                        elements = elements.toList(),
                    ),
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = "Roundtrip Model",
            )

        // ── Class with Properties and Operations ──────────────────────────────

        test("UmlClass mit Properties + Operations Roundtrip erhält alle Attribute") {
            val prop = UmlProperty(id = "Order.total", name = "total", type = UmlTypeRef("Double"))
            val op = UmlOperation(id = "Order.submit", name = "submit")
            val cls = UmlClass(id = "Order", name = "Order", attributes = listOf(prop), operations = listOf(op))

            val diagram = toUml.convert(toEmf.convert(kumlModel(cls))).root as KumlDiagram
            val classes = diagram.elements.filterIsInstance<UmlClass>()
            classes shouldHaveSize 1
            val roundtrip = classes.first()
            roundtrip.name shouldBe "Order"
            roundtrip.attributes shouldHaveSize 1
            roundtrip.attributes.first().name shouldBe "total"
            roundtrip.operations shouldHaveSize 1
            roundtrip.operations.first().name shouldBe "submit"
        }

        test("UmlClass isAbstract Roundtrip erhält isAbstract=true") {
            val cls = UmlClass(id = "Base", name = "Base", isAbstract = true)
            val diagram = toUml.convert(toEmf.convert(kumlModel(cls))).root as KumlDiagram
            diagram.elements
                .filterIsInstance<UmlClass>()
                .first()
                .isAbstract shouldBe true
        }

        test("UmlClass Visibility PRIVATE Roundtrip erhält Visibility") {
            val cls = UmlClass(id = "Secret", name = "Secret", visibility = Visibility.PRIVATE)
            val diagram = toUml.convert(toEmf.convert(kumlModel(cls))).root as KumlDiagram
            diagram.elements
                .filterIsInstance<UmlClass>()
                .first()
                .visibility shouldBe Visibility.PRIVATE
        }

        // ── Interface ─────────────────────────────────────────────────────────

        test("UmlInterface Roundtrip erhält Name und Operation") {
            val op = UmlOperation(id = "Drawable.draw", name = "draw")
            val iface = UmlInterface(id = "Drawable", name = "Drawable", operations = listOf(op))

            val diagram = toUml.convert(toEmf.convert(kumlModel(iface))).root as KumlDiagram
            val ifaces = diagram.elements.filterIsInstance<UmlInterface>()
            ifaces shouldHaveSize 1
            ifaces.first().name shouldBe "Drawable"
            ifaces.first().operations shouldHaveSize 1
            ifaces
                .first()
                .operations
                .first()
                .name shouldBe "draw"
        }

        // ── Enumeration ────────────────────────────────────────────────────────

        test("UmlEnumeration Roundtrip erhält alle Literals") {
            val enum =
                UmlEnumeration(
                    id = "Status",
                    name = "Status",
                    literals =
                        listOf(
                            UmlEnumerationLiteral(id = "Status_ACTIVE", name = "ACTIVE"),
                            UmlEnumerationLiteral(id = "Status_INACTIVE", name = "INACTIVE"),
                        ),
                )
            val diagram = toUml.convert(toEmf.convert(kumlModel(enum))).root as KumlDiagram
            val enums = diagram.elements.filterIsInstance<UmlEnumeration>()
            enums shouldHaveSize 1
            enums.first().literals shouldHaveSize 2
            enums
                .first()
                .literals
                .map { it.name }
                .toSet() shouldBe setOf("ACTIVE", "INACTIVE")
        }

        // ── Generalization ─────────────────────────────────────────────────────

        test("Generalization-Hierarchie Roundtrip: A extends B") {
            val parent = UmlClass(id = "Animal", name = "Animal", isAbstract = true)
            val child = UmlClass(id = "Cat", name = "Cat")
            val gen = UmlGeneralization(id = "Cat-gen-Animal", specificId = "Cat", generalId = "Animal")

            val diagram = toUml.convert(toEmf.convert(kumlModel(parent, child, gen))).root as KumlDiagram
            val gens = diagram.elements.filterIsInstance<UmlGeneralization>()
            gens shouldHaveSize 1
            gens.first().specificId shouldBe "Cat"
            gens.first().generalId shouldBe "Animal"
        }

        // ── Dependency ────────────────────────────────────────────────────────

        test("UmlDependency Roundtrip erhält client und supplier") {
            val client = UmlClass(id = "Controller", name = "Controller")
            val supplier = UmlClass(id = "Service", name = "Service")
            val dep = UmlDependency(id = "dep-Controller-Service", clientId = "Controller", supplierId = "Service")

            val diagram = toUml.convert(toEmf.convert(kumlModel(client, supplier, dep))).root as KumlDiagram
            val deps = diagram.elements.filterIsInstance<UmlDependency>()
            deps shouldHaveSize 1
            deps.first().clientId shouldBe "Controller"
            deps.first().supplierId shouldBe "Service"
        }

        // ── Association ────────────────────────────────────────────────────────

        test("UmlAssociation (beide navigabel) Roundtrip erhält beide Ends") {
            val cls1 = UmlClass(id = "Customer", name = "Customer")
            val cls2 = UmlClass(id = "Order", name = "Order")
            val assoc =
                UmlAssociation(
                    id = "Customer-Order",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Customer", role = "customer", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "Order", role = "orders", multiplicity = Multiplicity(0, null)),
                        ),
                )

            val diagram = toUml.convert(toEmf.convert(kumlModel(cls1, cls2, assoc))).root as KumlDiagram
            val assocs = diagram.elements.filterIsInstance<UmlAssociation>()
            assocs shouldHaveSize 1
            assocs.first().ends shouldHaveSize 2
        }

        // ── Mixed model ────────────────────────────────────────────────────────

        test("Gemischtes Modell: 2 Classes + 1 Interface + Generalization + Realization") {
            val baseClass = UmlClass(id = "BaseEntity", name = "BaseEntity", isAbstract = true)
            val concreteClass = UmlClass(id = "User", name = "User")
            val iface = UmlInterface(id = "Auditable", name = "Auditable")
            val gen = UmlGeneralization(id = "User-gen-BaseEntity", specificId = "User", generalId = "BaseEntity")
            val real = UmlInterfaceRealization(id = "User-real-Auditable", implementingId = "User", interfaceId = "Auditable")

            val diagram = toUml.convert(toEmf.convert(kumlModel(baseClass, concreteClass, iface, gen, real))).root as KumlDiagram
            diagram.elements.filterIsInstance<UmlClass>() shouldHaveSize 2
            diagram.elements.filterIsInstance<UmlInterface>() shouldHaveSize 1
            diagram.elements.filterIsInstance<UmlGeneralization>() shouldHaveSize 1
            diagram.elements.filterIsInstance<UmlInterfaceRealization>() shouldHaveSize 1
        }

        test("Property Multiplicity 0..* Roundtrip bleibt unbounded") {
            val prop = UmlProperty(id = "Container.items", name = "items", type = UmlTypeRef("Item"), multiplicity = Multiplicity(0, null))
            val cls = UmlClass(id = "Container", name = "Container", attributes = listOf(prop))

            val diagram = toUml.convert(toEmf.convert(kumlModel(cls))).root as KumlDiagram
            val attr =
                diagram.elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .attributes
                    .first()
            attr.multiplicity.lower shouldBe 0
            attr.multiplicity.upper shouldBe null
        }

        test("Operation returnType Roundtrip bleibt erhalten") {
            val op = UmlOperation(id = "Math.sqrt", name = "sqrt", returnType = UmlTypeRef("Double"))
            val cls = UmlClass(id = "Math", name = "Math", operations = listOf(op))

            val diagram = toUml.convert(toEmf.convert(kumlModel(cls))).root as KumlDiagram
            val opResult =
                diagram.elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .operations
                    .first()
            opResult.returnType shouldNotBe null
            opResult.returnType!!.name shouldBe "Double"
        }
    })
