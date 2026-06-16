package dev.kuml.io.emf

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.ParameterDirection
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlParameter
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.eclipse.uml2.uml.Enumeration
import org.eclipse.uml2.uml.Interface
import org.eclipse.uml2.uml.LiteralUnlimitedNatural
import org.eclipse.uml2.uml.VisibilityKind
import org.eclipse.uml2.uml.Class as EmfClass

class UmlToEmfConverterFullTest :
    FunSpec({

        val converter = UmlToEmfConverter()

        beforeSpec { EmfBootstrap.init() }

        fun kumlModel(vararg elements: dev.kuml.core.model.KumlElement): KumlModel =
            KumlModel(
                root =
                    KumlDiagram(
                        id = "test-diagram",
                        name = "Test Diagram",
                        type = DiagramType.CLASS,
                        elements = elements.toList(),
                    ),
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = "Test Model",
            )

        // ── Interface ──────────────────────────────────────────────────────────

        test("UmlInterface → EMF Interface — Name wird gesetzt") {
            val iface = UmlInterface(id = "Printable", name = "Printable")
            val emfModel = converter.convert(kumlModel(iface))
            val emfIfaces = emfModel.ownedTypes.filterIsInstance<Interface>()
            emfIfaces shouldHaveSize 1
            emfIfaces.first().name shouldBe "Printable"
        }

        test("UmlInterface mit UmlOperation → EMF Interface mit Operation") {
            val op = UmlOperation(id = "Printable.print", name = "print")
            val iface = UmlInterface(id = "Printable", name = "Printable", operations = listOf(op))
            val emfModel = converter.convert(kumlModel(iface))
            val emfIface = emfModel.ownedTypes.filterIsInstance<Interface>().first()
            emfIface.ownedOperations shouldHaveSize 1
            emfIface.ownedOperations.first().name shouldBe "print"
        }

        test("UmlInterface Visibility PROTECTED → EMF PROTECTED_LITERAL") {
            val iface = UmlInterface(id = "ProtIface", name = "ProtIface", visibility = Visibility.PROTECTED)
            val emfModel = converter.convert(kumlModel(iface))
            val emfIface = emfModel.ownedTypes.filterIsInstance<Interface>().first()
            emfIface.visibility shouldBe VisibilityKind.PROTECTED_LITERAL
        }

        // ── Enumeration ────────────────────────────────────────────────────────

        test("UmlEnumeration → EMF Enumeration — Literals werden erstellt") {
            val enum =
                UmlEnumeration(
                    id = "Color",
                    name = "Color",
                    literals =
                        listOf(
                            UmlEnumerationLiteral(id = "Color_RED", name = "RED"),
                            UmlEnumerationLiteral(id = "Color_GREEN", name = "GREEN"),
                            UmlEnumerationLiteral(id = "Color_BLUE", name = "BLUE"),
                        ),
                )
            val emfModel = converter.convert(kumlModel(enum))
            val emfEnum = emfModel.ownedTypes.filterIsInstance<Enumeration>().first()
            emfEnum.name shouldBe "Color"
            emfEnum.ownedLiterals shouldHaveSize 3
            emfEnum.ownedLiterals.map { it.name }.toSet() shouldBe setOf("RED", "GREEN", "BLUE")
        }

        test("UmlEnumeration ohne Literals → EMF Enumeration ohne Literals") {
            val enum = UmlEnumeration(id = "Status", name = "Status")
            val emfModel = converter.convert(kumlModel(enum))
            val emfEnum = emfModel.ownedTypes.filterIsInstance<Enumeration>().first()
            emfEnum.ownedLiterals.shouldBeEmpty()
        }

        // ── Property ──────────────────────────────────────────────────────────

        test("UmlProperty mit Multiplicity 0..* → EMF upper=-1") {
            val prop = UmlProperty(id = "cls.items", name = "items", type = UmlTypeRef("String"), multiplicity = Multiplicity(0, null))
            val cls = UmlClass(id = "Order", name = "Order", attributes = listOf(prop))
            val emfModel = converter.convert(kumlModel(cls))
            val emfCls = emfModel.ownedTypes.filterIsInstance<EmfClass>().first()
            val emfProp = emfCls.ownedAttributes.first()
            emfProp.lower shouldBe 0
            emfProp.upper shouldBe LiteralUnlimitedNatural.UNLIMITED
        }

        test("UmlProperty isStatic=true → EMF isStatic=true") {
            val prop = UmlProperty(id = "Counter.count", name = "count", type = UmlTypeRef("Int"), isStatic = true)
            val cls = UmlClass(id = "Counter", name = "Counter", attributes = listOf(prop))
            val emfModel = converter.convert(kumlModel(cls))
            val emfProp =
                emfModel.ownedTypes
                    .filterIsInstance<EmfClass>()
                    .first()
                    .ownedAttributes
                    .first()
            emfProp.isStatic shouldBe true
        }

        test("UmlProperty isReadOnly=true → EMF isReadOnly=true") {
            val prop = UmlProperty(id = "Config.version", name = "version", type = UmlTypeRef("String"), isReadOnly = true)
            val cls = UmlClass(id = "Config", name = "Config", attributes = listOf(prop))
            val emfModel = converter.convert(kumlModel(cls))
            val emfProp =
                emfModel.ownedTypes
                    .filterIsInstance<EmfClass>()
                    .first()
                    .ownedAttributes
                    .first()
            emfProp.isReadOnly shouldBe true
        }

        // ── Operation ─────────────────────────────────────────────────────────

        test("UmlOperation mit returnType → EMF Operation mit return Parameter") {
            val op = UmlOperation(id = "Calculator.compute", name = "compute", returnType = UmlTypeRef("Double"))
            val cls = UmlClass(id = "Calculator", name = "Calculator", operations = listOf(op))
            val emfModel = converter.convert(kumlModel(cls))
            val emfOp =
                emfModel.ownedTypes
                    .filterIsInstance<EmfClass>()
                    .first()
                    .ownedOperations
                    .first()
            emfOp.name shouldBe "compute"
            emfOp.getReturnResult() shouldNotBe null
            emfOp.getReturnResult()!!.type?.name shouldBe "Double"
        }

        test("UmlOperation mit IN-Parameter → EMF Parameter mit IN direction") {
            val param = UmlParameter(id = "add.x", name = "x", type = UmlTypeRef("Int"), direction = ParameterDirection.IN)
            val op = UmlOperation(id = "Calculator.add", name = "add", parameters = listOf(param))
            val cls = UmlClass(id = "Calculator", name = "Calculator", operations = listOf(op))
            val emfModel = converter.convert(kumlModel(cls))
            val emfOp =
                emfModel.ownedTypes
                    .filterIsInstance<EmfClass>()
                    .first()
                    .ownedOperations
                    .first()
            val emfParams = emfOp.inputParameters()
            emfParams shouldHaveSize 1
            emfParams.first().name shouldBe "x"
        }

        test("UmlOperation isAbstract=true → EMF isAbstract=true") {
            val op = UmlOperation(id = "Shape.draw", name = "draw", isAbstract = true)
            val cls = UmlClass(id = "Shape", name = "Shape", isAbstract = true, operations = listOf(op))
            val emfModel = converter.convert(kumlModel(cls))
            val emfOp =
                emfModel.ownedTypes
                    .filterIsInstance<EmfClass>()
                    .first()
                    .ownedOperations
                    .first()
            emfOp.isAbstract shouldBe true
        }

        // ── Generalization ─────────────────────────────────────────────────────

        test("UmlGeneralization → EMF Generalization in specificClass") {
            val parent = UmlClass(id = "Animal", name = "Animal", isAbstract = true)
            val child = UmlClass(id = "Dog", name = "Dog")
            val gen = UmlGeneralization(id = "Dog-gen-Animal", specificId = "Dog", generalId = "Animal")
            val emfModel = converter.convert(kumlModel(parent, child, gen))
            val emfDog = emfModel.ownedTypes.filterIsInstance<EmfClass>().first { it.name == "Dog" }
            emfDog.generalizations shouldHaveSize 1
            emfDog.generalizations
                .first()
                .general
                ?.name shouldBe "Animal"
        }

        // ── InterfaceRealization ───────────────────────────────────────────────

        test("UmlInterfaceRealization → EMF InterfaceRealization in implementingClass") {
            val iface = UmlInterface(id = "Flyable", name = "Flyable")
            val cls = UmlClass(id = "Bird", name = "Bird")
            val real = UmlInterfaceRealization(id = "Bird-real-Flyable", implementingId = "Bird", interfaceId = "Flyable")
            val emfModel = converter.convert(kumlModel(iface, cls, real))
            val emfBird = emfModel.ownedTypes.filterIsInstance<EmfClass>().first { it.name == "Bird" }
            emfBird.interfaceRealizations shouldHaveSize 1
            emfBird.interfaceRealizations
                .first()
                .contract
                ?.name shouldBe "Flyable"
        }

        // ── Dependency ────────────────────────────────────────────────────────

        test("UmlDependency → EMF Dependency im Model") {
            val client = UmlClass(id = "Service", name = "Service")
            val supplier = UmlClass(id = "Repository", name = "Repository")
            val dep = UmlDependency(id = "dep-Service-Repository", clientId = "Service", supplierId = "Repository", name = "uses")
            val emfModel = converter.convert(kumlModel(client, supplier, dep))
            val deps = emfModel.packagedElements.filterIsInstance<org.eclipse.uml2.uml.Dependency>()
            deps shouldHaveSize 1
            deps
                .first()
                .clients
                .first()
                .name shouldBe "Service"
            deps
                .first()
                .suppliers
                .first()
                .name shouldBe "Repository"
        }
    })
