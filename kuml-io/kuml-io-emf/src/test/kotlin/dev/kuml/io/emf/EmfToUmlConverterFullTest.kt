package dev.kuml.io.emf

import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.eclipse.uml2.uml.UMLFactory
import org.eclipse.uml2.uml.VisibilityKind

class EmfToUmlConverterFullTest :
    FunSpec({

        val converter = EmfToUmlConverter()

        beforeSpec { EmfBootstrap.init() }

        fun emfModel(name: String = "TestModel") = UMLFactory.eINSTANCE.createModel().also { it.name = name }

        // ── Interface ──────────────────────────────────────────────────────────

        test("EMF Interface wird als UmlInterface konvertiert") {
            val m = emfModel()
            m.createOwnedInterface("Printable")
            val diagram = converter.convert(m).root as KumlDiagram
            val ifaces = diagram.elements.filterIsInstance<UmlInterface>()
            ifaces shouldHaveSize 1
            ifaces.first().name shouldBe "Printable"
        }

        test("EMF Interface mit Operation wird korrekt konvertiert") {
            val m = emfModel()
            val iface = m.createOwnedInterface("Serializable")
            iface.createOwnedOperation("serialize", null, null)
            val diagram = converter.convert(m).root as KumlDiagram
            val ifaces = diagram.elements.filterIsInstance<UmlInterface>()
            ifaces.first().operations shouldHaveSize 1
            ifaces
                .first()
                .operations
                .first()
                .name shouldBe "serialize"
        }

        test("EMF Interface Visibility PROTECTED wird gemappt") {
            val m = emfModel()
            val iface = m.createOwnedInterface("ProtIface")
            iface.visibility = VisibilityKind.PROTECTED_LITERAL
            val diagram = converter.convert(m).root as KumlDiagram
            diagram.elements
                .filterIsInstance<UmlInterface>()
                .first()
                .visibility shouldBe Visibility.PROTECTED
        }

        // ── Enumeration ────────────────────────────────────────────────────────

        test("EMF Enumeration wird als UmlEnumeration konvertiert") {
            val m = emfModel()
            val e = m.createOwnedEnumeration("Color")
            e.createOwnedLiteral("RED")
            e.createOwnedLiteral("GREEN")
            e.createOwnedLiteral("BLUE")
            val diagram = converter.convert(m).root as KumlDiagram
            val enums = diagram.elements.filterIsInstance<UmlEnumeration>()
            enums shouldHaveSize 1
            enums.first().name shouldBe "Color"
            enums.first().literals shouldHaveSize 3
            enums
                .first()
                .literals
                .map { it.name }
                .toSet() shouldBe setOf("RED", "GREEN", "BLUE")
        }

        test("leere EMF Enumeration ergibt UmlEnumeration ohne Literals") {
            val m = emfModel()
            m.createOwnedEnumeration("Status")
            val diagram = converter.convert(m).root as KumlDiagram
            diagram.elements
                .filterIsInstance<UmlEnumeration>()
                .first()
                .literals
                .shouldBeEmpty()
        }

        // ── Property ──────────────────────────────────────────────────────────

        test("EMF Property mit Multiplicity 0..* wird als 0..null konvertiert") {
            val m = emfModel()
            val cls = m.createOwnedClass("Order", false)
            val pt = m.createOwnedPrimitiveType("String")
            val prop = cls.createOwnedAttribute("items", pt)
            prop.setLower(0)
            prop.setUpper(-1) // unbounded
            val diagram = converter.convert(m).root as KumlDiagram
            val attrs =
                diagram.elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .attributes
            attrs shouldHaveSize 1
            attrs.first().multiplicity.lower shouldBe 0
            attrs.first().multiplicity.upper shouldBe null
        }

        test("EMF Property isStatic=true wird korrekt konvertiert") {
            val m = emfModel()
            val cls = m.createOwnedClass("Counter", false)
            val pt = m.createOwnedPrimitiveType("Int")
            val prop = cls.createOwnedAttribute("count", pt)
            prop.setIsStatic(true)
            val diagram = converter.convert(m).root as KumlDiagram
            val attr =
                diagram.elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .attributes
                    .first()
            attr.isStatic shouldBe true
        }

        test("EMF Property isReadOnly=true wird korrekt konvertiert") {
            val m = emfModel()
            val cls = m.createOwnedClass("Config", false)
            val pt = m.createOwnedPrimitiveType("String")
            val prop = cls.createOwnedAttribute("version", pt)
            prop.setIsReadOnly(true)
            val diagram = converter.convert(m).root as KumlDiagram
            val attr =
                diagram.elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .attributes
                    .first()
            attr.isReadOnly shouldBe true
        }

        // ── Operation ─────────────────────────────────────────────────────────

        test("EMF Operation mit returnType wird korrekt konvertiert") {
            val m = emfModel()
            val cls = m.createOwnedClass("Calculator", false)
            val retType = m.createOwnedPrimitiveType("Double")
            cls.createOwnedOperation("compute", null, null, retType)
            val diagram = converter.convert(m).root as KumlDiagram
            val op =
                diagram.elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .operations
                    .first()
            op.name shouldBe "compute"
            op.returnType shouldNotBe null
            op.returnType!!.name shouldBe "Double"
        }

        // ── Association ────────────────────────────────────────────────────────

        test("EMF Association zwischen zwei Klassen wird konvertiert") {
            val m = emfModel()
            val cls1 = m.createOwnedClass("Customer", false)
            val cls2 = m.createOwnedClass("Order", false)

            val assoc =
                m.createOwnedType(
                    "customerOrders",
                    org.eclipse.uml2.uml.UMLPackage.eINSTANCE.association,
                ) as org.eclipse.uml2.uml.Association
            assoc.createNavigableOwnedEnd("orders", cls2)
            assoc.createNavigableOwnedEnd("customer", cls1)

            val diagram = converter.convert(m).root as KumlDiagram
            val assocs = diagram.elements.filterIsInstance<UmlAssociation>()
            assocs shouldHaveSize 1
            assocs.first().ends shouldHaveSize 2
        }

        // ── Generalization ─────────────────────────────────────────────────────

        test("EMF Generalization wird aus specificClass.generalizations extrahiert") {
            val m = emfModel()
            val parent = m.createOwnedClass("Animal", true)
            val child = m.createOwnedClass("Dog", false)
            child.createGeneralization(parent)

            val diagram = converter.convert(m).root as KumlDiagram
            val gens = diagram.elements.filterIsInstance<UmlGeneralization>()
            gens shouldHaveSize 1
            gens.first().specificId shouldBe "Dog"
            gens.first().generalId shouldBe "Animal"
        }

        // ── InterfaceRealization ───────────────────────────────────────────────

        test("EMF InterfaceRealization wird extrahiert") {
            val m = emfModel()
            val iface = m.createOwnedInterface("Flyable")
            val cls = m.createOwnedClass("Bird", false)
            cls.createInterfaceRealization(null, iface)

            val diagram = converter.convert(m).root as KumlDiagram
            val reals = diagram.elements.filterIsInstance<UmlInterfaceRealization>()
            reals shouldHaveSize 1
            reals.first().implementingId shouldBe "Bird"
            reals.first().interfaceId shouldBe "Flyable"
        }

        // ── Dependency ────────────────────────────────────────────────────────

        test("EMF Dependency zwischen zwei Elementen wird konvertiert") {
            val m = emfModel()
            val client = m.createOwnedClass("Service", false)
            val supplier = m.createOwnedClass("Repository", false)
            val dep = UMLFactory.eINSTANCE.createDependency()
            dep.name = "uses"
            dep.clients.add(client)
            dep.suppliers.add(supplier)
            m.packagedElements.add(dep)

            val diagram = converter.convert(m).root as KumlDiagram
            val deps = diagram.elements.filterIsInstance<UmlDependency>()
            deps shouldHaveSize 1
            deps.first().clientId shouldBe "Service"
            deps.first().supplierId shouldBe "Repository"
        }

        // ── Mixed model ────────────────────────────────────────────────────────

        test("Gemischtes Modell mit Class, Interface, Enumeration wird vollständig konvertiert") {
            val m = emfModel()
            m.createOwnedClass("Product", false)
            m.createOwnedInterface("Storable")
            m.createOwnedEnumeration("Category").also {
                it.createOwnedLiteral("FOOD")
                it.createOwnedLiteral("TECH")
            }

            val diagram = converter.convert(m).root as KumlDiagram
            diagram.elements.filterIsInstance<UmlClass>() shouldHaveSize 1
            diagram.elements.filterIsInstance<UmlInterface>() shouldHaveSize 1
            diagram.elements.filterIsInstance<UmlEnumeration>() shouldHaveSize 1
        }
    })
