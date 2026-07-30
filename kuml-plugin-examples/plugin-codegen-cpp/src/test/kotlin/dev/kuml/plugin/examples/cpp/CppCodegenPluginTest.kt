package dev.kuml.plugin.examples.cpp

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginPermission
import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files

class CppCodegenPluginTest :
    FunSpec({

        val plugin = CppCodegenPlugin()
        val generator = plugin.generators().first()
        val cppGenerator = generator as CppCodeGenerator

        fun tempDir(): File =
            Files
                .createTempDirectory("cpp-codegen-test")
                .toFile()
                .also { it.deleteOnExit() }

        fun diagram(vararg elements: KumlElement) =
            KumlDiagram(
                id = "test",
                name = "Test",
                type = DiagramType.CLASS,
                elements = elements.toList(),
            )

        // ── Plugin descriptor ──────────────────────────────────────────────────

        test("generators() liefert genau einen Generator") {
            plugin.generators() shouldHaveSize 1
        }

        test("generator id ist cpp") {
            generator.id shouldBe "cpp"
        }

        test("descriptor capabilities enthält CODEGEN") {
            plugin.descriptor.capabilities shouldContain PluginCapability.CODEGEN
        }

        test("descriptor requiredPermissions enthält FS_WRITE") {
            plugin.descriptor.requiredPermissions shouldContain PluginPermission.FS_WRITE
        }

        test("descriptor kumlVersionRange enthält 0.12.0") {
            plugin.descriptor.kumlVersionRange.contains(PluginVersion(major = 0, minor = 12, patch = 0)) shouldBe true
        }

        // ── Basic generation ───────────────────────────────────────────────────

        test("leeres Diagram → leere Datei-Liste") {
            generator.generate(diagram = diagram(), outputDir = tempDir(), options = emptyMap()) shouldHaveSize 0
        }

        test("UmlClass erzeugt .hpp und .cpp") {
            val cls = UmlClass(id = "Order", name = "Order")
            val files = generator.generate(diagram = diagram(cls), outputDir = tempDir(), options = emptyMap())
            files shouldHaveSize 2
            files.map { it.name } shouldContain "Order.hpp"
            files.map { it.name } shouldContain "Order.cpp"
        }

        test("generateCpp=false unterdrückt .cpp") {
            val cls = UmlClass(id = "Order", name = "Order")
            val files = generator.generate(diagram = diagram(cls), outputDir = tempDir(), options = mapOf("generateCpp" to "false"))
            files shouldHaveSize 1
            files.first().name shouldBe "Order.hpp"
        }

        test("Header enthält #pragma once") {
            val cls = UmlClass(id = "Order", name = "Order")
            val out = tempDir()
            generator.generate(diagram = diagram(cls), outputDir = out, options = emptyMap())
            File(out, "Order.hpp").readText() shouldContain "#pragma once"
        }

        test("Header enthält class Order") {
            val cls = UmlClass(id = "Order", name = "Order")
            val out = tempDir()
            generator.generate(diagram = diagram(cls), outputDir = out, options = emptyMap())
            File(out, "Order.hpp").readText() shouldContain "class Order"
        }

        test("UmlClass mit 3 Properties und 2 Operationen erzeugt korrekten .hpp") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    attributes =
                        listOf(
                            UmlProperty(id = "Order.id", name = "id", type = UmlTypeRef(name = "String")),
                            UmlProperty(id = "Order.total", name = "total", type = UmlTypeRef(name = "Double")),
                            UmlProperty(id = "Order.count", name = "count", type = UmlTypeRef(name = "Int")),
                        ),
                    operations =
                        listOf(
                            UmlOperation(id = "Order.submit", name = "submit", returnType = UmlTypeRef(name = "Void")),
                            UmlOperation(
                                id = "Order.totalSum",
                                name = "totalSum",
                                returnType = UmlTypeRef(name = "Double"),
                            ),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram = diagram(cls), outputDir = out, options = emptyMap())
            val content = File(out, "Order.hpp").readText()
            content shouldContain "std::string id_;"
            content shouldContain "double total_;"
            content shouldContain "int count_;"
            content shouldContain "void submit();"
            content shouldContain "double totalSum();"
        }

        // ── Interface / abstract class ─────────────────────────────────────────

        test("UmlInterface erzeugt abstrakte Klasse mit pure-virtual") {
            val iface =
                UmlInterface(
                    id = "Orderable",
                    name = "Orderable",
                    operations =
                        listOf(
                            UmlOperation(id = "Orderable.process", name = "process", returnType = UmlTypeRef(name = "Void")),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram = diagram(iface), outputDir = out, options = emptyMap())
            val content = File(out, "Orderable.hpp").readText()
            content shouldContain "class Orderable"
            content shouldContain "= 0;"
            content shouldContain "virtual ~Orderable() = default;"
        }

        test("Interface erzeugt keine .cpp") {
            val iface = UmlInterface(id = "Orderable", name = "Orderable")
            val files = generator.generate(diagram = diagram(iface), outputDir = tempDir(), options = emptyMap())
            files shouldHaveSize 1
            files.first().name shouldBe "Orderable.hpp"
        }

        // ── Inheritance ────────────────────────────────────────────────────────

        test("UmlGeneralization erzeugt : public BaseClass") {
            val base = UmlClass(id = "Base", name = "Base")
            val derived = UmlClass(id = "Derived", name = "Derived")
            val gen = UmlGeneralization(id = "g1", specificId = "Derived", generalId = "Base")
            val out = tempDir()
            generator.generate(diagram = diagram(base, derived, gen), outputDir = out, options = emptyMap())
            File(out, "Derived.hpp").readText() shouldContain "class Derived : public Base"
        }

        // ── Association ────────────────────────────────────────────────────────

        test("Association 0..* erzeugt std::vector<T*>") {
            val customer = UmlClass(id = "Customer", name = "Customer")
            val order = UmlClass(id = "Order", name = "Order")
            val assoc =
                UmlAssociation(
                    id = "a1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Customer", multiplicity = Multiplicity(lower = 1, upper = 1), navigable = false),
                            UmlAssociationEnd(
                                typeId = "Order",
                                role = "orders",
                                multiplicity = Multiplicity(lower = 0, upper = null),
                                navigable = true,
                            ),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram = diagram(customer, order, assoc), outputDir = out, options = emptyMap())
            File(out, "Customer.hpp").readText() shouldContain "std::vector<Order*>"
        }

        test("Association 0..1 erzeugt T* Pointer") {
            val customer = UmlClass(id = "Customer", name = "Customer")
            val order = UmlClass(id = "Order", name = "Order")
            val assoc =
                UmlAssociation(
                    id = "a1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Customer", multiplicity = Multiplicity(lower = 1, upper = 1), navigable = false),
                            UmlAssociationEnd(
                                typeId = "Order",
                                role = "order",
                                multiplicity = Multiplicity(lower = 0, upper = 1),
                                navigable = true,
                            ),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram = diagram(customer, order, assoc), outputDir = out, options = emptyMap())
            val content = File(out, "Customer.hpp").readText()
            content shouldContain "Order*"
        }

        test("useSmartPointers=true erzeugt std::shared_ptr") {
            val customer = UmlClass(id = "Customer", name = "Customer")
            val order = UmlClass(id = "Order", name = "Order")
            val assoc =
                UmlAssociation(
                    id = "a1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Customer", multiplicity = Multiplicity(lower = 1, upper = 1), navigable = false),
                            UmlAssociationEnd(
                                typeId = "Order",
                                role = "orders",
                                multiplicity = Multiplicity(lower = 0, upper = null),
                                navigable = true,
                            ),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram = diagram(customer, order, assoc), outputDir = out, options = mapOf("useSmartPointers" to "true"))
            File(out, "Customer.hpp").readText() shouldContain "std::vector<std::shared_ptr<Order>>"
        }

        test("Header für Association mit 0..* enthält #include <vector>") {
            val customer = UmlClass(id = "Customer", name = "Customer")
            val order = UmlClass(id = "Order", name = "Order")
            val assoc =
                UmlAssociation(
                    id = "a1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Customer", multiplicity = Multiplicity(lower = 1, upper = 1), navigable = false),
                            UmlAssociationEnd(
                                typeId = "Order",
                                role = "orders",
                                multiplicity = Multiplicity(lower = 0, upper = null),
                                navigable = true,
                            ),
                        ),
                )
            val out = tempDir()
            generator.generate(
                diagram = diagram(customer, order, assoc),
                outputDir = out,
                options = mapOf("useSmartPointers" to "true"),
            )
            val content = File(out, "Customer.hpp").readText()
            content shouldContain "#include <vector>"
            content shouldContain "#include <memory>"
        }

        // ── Enumeration ────────────────────────────────────────────────────────

        test("UmlEnumeration erzeugt enum class") {
            val enum =
                UmlEnumeration(
                    id = "Status",
                    name = "Status",
                    literals =
                        listOf(
                            UmlEnumerationLiteral(id = "Status.DRAFT", name = "DRAFT"),
                            UmlEnumerationLiteral(id = "Status.DONE", name = "DONE"),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram = diagram(enum), outputDir = out, options = emptyMap())
            val content = File(out, "Status.hpp").readText()
            content shouldContain "enum class Status"
            content shouldContain "DRAFT"
            content shouldContain "DONE"
        }

        // ── Type mapping ───────────────────────────────────────────────────────

        val mapper = CppTypeMapper()

        test("mapType: String → std::string") {
            mapper.mapType("String") shouldBe "std::string"
        }

        test("mapType: Int → int") {
            mapper.mapType("Int") shouldBe "int"
        }

        test("mapType: Boolean → bool") {
            mapper.mapType("Boolean") shouldBe "bool"
        }

        test("mapType: Double → double") {
            mapper.mapType("Double") shouldBe "double"
        }

        test("mapType: Long → long") {
            mapper.mapType("Long") shouldBe "long"
        }

        test("mapType: unbekannter Typ wird durchgereicht") {
            mapper.mapType("MyType") shouldBe "MyType"
        }

        // ── Naming convention ──────────────────────────────────────────────────

        test("CppNamingConvention.SNAKE_CASE: firstName → first_name") {
            CppNamingConvention.SNAKE_CASE.apply("firstName") shouldBe "first_name"
        }

        test("DEFAULT lässt Namen unverändert: firstName → firstName") {
            CppNamingConvention.DEFAULT.apply("firstName") shouldBe "firstName"
        }

        test("SNAKE_CASE wird NICHT auf Typnamen angewendet") {
            val cls = UmlClass(id = "OrderItem", name = "OrderItem")
            val out = tempDir()
            generator.generate(diagram = diagram(cls), outputDir = out, options = mapOf("naming" to "snake_case"))
            val content = File(out, "OrderItem.hpp").readText()
            content shouldContain "class OrderItem"
        }

        test("Namespace nested vs flat — flat emittiert namespace a::b") {
            val cls = UmlClass(id = "Foo", name = "Foo")
            val out = tempDir()
            generator.generate(
                diagram = diagram(cls),
                outputDir = out,
                options =
                    mapOf(
                        "namespace" to "app::model",
                        "namespaceStyle" to "flat",
                    ),
            )
            File(out, "Foo.hpp").readText() shouldContain "namespace app::model {"
        }

        test("Namespace nested emittiert namespace app { namespace model") {
            val cls = UmlClass(id = "Foo", name = "Foo")
            val out = tempDir()
            generator.generate(
                diagram = diagram(cls),
                outputDir = out,
                options =
                    mapOf(
                        "namespace" to "app::model",
                        "namespaceStyle" to "nested",
                    ),
            )
            val content = File(out, "Foo.hpp").readText()
            content shouldContain "namespace app {"
            content shouldContain "namespace model {"
        }

        test("kein namespaceName → kein namespace-Block") {
            val cls = UmlClass(id = "Foo", name = "Foo")
            val out = tempDir()
            generator.generate(diagram = diagram(cls), outputDir = out, options = emptyMap())
            File(out, "Foo.hpp").readText() shouldNotContain "namespace"
        }

        // ── Inheritance includes ───────────────────────────────────────────────

        test("Generalization erzeugt #include für Basisklasse in Derived.hpp") {
            val base = UmlClass(id = "Base", name = "Base")
            val derived = UmlClass(id = "Derived", name = "Derived")
            val gen = UmlGeneralization(id = "g1", specificId = "Derived", generalId = "Base")
            val out = tempDir()
            generator.generate(diagram = diagram(base, derived, gen), outputDir = out, options = emptyMap())
            val content = File(out, "Derived.hpp").readText()
            content shouldContain "#include \"Base.hpp\""
        }

        test("Klasse ohne Basisklasse enthält kein Basis-Include") {
            val cls = UmlClass(id = "Solo", name = "Solo")
            val out = tempDir()
            generator.generate(diagram = diagram(cls), outputDir = out, options = emptyMap())
            File(out, "Solo.hpp").readText() shouldNotContain "#include \"Solo.hpp\""
        }

        // ── Forward declarations ──────────────────────────────────────────────

        test("Association 0..1 erzeugt Forward-Declaration für Pointer-Target") {
            val customer = UmlClass(id = "Customer", name = "Customer")
            val order = UmlClass(id = "Order", name = "Order")
            val assoc =
                UmlAssociation(
                    id = "a1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Customer", multiplicity = Multiplicity(lower = 1, upper = 1), navigable = false),
                            UmlAssociationEnd(
                                typeId = "Order",
                                role = "order",
                                multiplicity = Multiplicity(lower = 0, upper = 1),
                                navigable = true,
                            ),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram = diagram(customer, order, assoc), outputDir = out, options = emptyMap())
            val content = File(out, "Customer.hpp").readText()
            content shouldContain "class Order;"
        }

        test("Association 0..* erzeugt #include statt Forward-Declaration (vector braucht vollständigen Typ)") {
            val customer = UmlClass(id = "Customer", name = "Customer")
            val order = UmlClass(id = "Order", name = "Order")
            val assoc =
                UmlAssociation(
                    id = "a1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Customer", multiplicity = Multiplicity(lower = 1, upper = 1), navigable = false),
                            UmlAssociationEnd(
                                typeId = "Order",
                                role = "orders",
                                multiplicity = Multiplicity(lower = 0, upper = null),
                                navigable = true,
                            ),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram = diagram(customer, order, assoc), outputDir = out, options = emptyMap())
            val content = File(out, "Customer.hpp").readText()
            content shouldContain "#include \"Order.hpp\""
        }

        // ── UmlPackage → namespace mapping ────────────────────────────────────

        test("UmlPackage-Mitglieder erhalten Namespace aus Package-Name") {
            val pkg =
                UmlPackage(
                    id = "model",
                    name = "model",
                    members = listOf(UmlClass(id = "Foo", name = "Foo")),
                )
            val out = tempDir()
            generator.generate(diagram = diagram(pkg), outputDir = out, options = mapOf("namespaceStyle" to "flat"))
            val content = File(out, "Foo.hpp").readText()
            content shouldContain "namespace model {"
        }

        test("Verschachtelter UmlPackage erzeugt geschachtelten Namespace") {
            val inner =
                UmlPackage(
                    id = "inner",
                    name = "inner",
                    members = listOf(UmlClass(id = "Bar", name = "Bar")),
                )
            val outer = UmlPackage(id = "outer", name = "outer", members = listOf(inner))
            val out = tempDir()
            generator.generate(diagram = diagram(outer), outputDir = out, options = mapOf("namespaceStyle" to "nested"))
            val content = File(out, "Bar.hpp").readText()
            content shouldContain "namespace outer {"
            content shouldContain "namespace inner {"
        }

        // ── Security: path-traversal sanitization ─────────────────────────────

        test("Elementname mit Pfad-Traversal-Sequenz wird sanitisiert (kein Schreiben außerhalb outputDir)") {
            // A UML element named "../../evil" must NOT produce a file outside outputDir.
            val cls = UmlClass(id = "evil", name = "../../evil")
            val out = tempDir()
            val files = generator.generate(diagram = diagram(cls), outputDir = out, options = emptyMap())
            // The generated file must be a direct child of outputDir.
            files.forEach { file ->
                file.canonicalPath.startsWith(out.canonicalPath) shouldBe true
            }
            // The file name must not contain path separators or traversal sequences.
            files.forEach { file ->
                file.name shouldNotContain "/"
                file.name shouldNotContain "\\"
                file.name shouldNotContain ".."
            }
        }

        test("Elementname mit Schrägstrich wird zu Unterstrich") {
            val cls = UmlClass(id = "myclass", name = "my/class")
            val out = tempDir()
            val files = generator.generate(diagram = diagram(cls), outputDir = out, options = emptyMap())
            files.map { it.name } shouldContain "my_class.hpp"
        }

        test("Package-Namespace hat Vorrang vor globalem namespace-Option") {
            val pkg =
                UmlPackage(
                    id = "domain",
                    name = "domain",
                    members = listOf(UmlClass(id = "Entity", name = "Entity")),
                )
            val out = tempDir()
            generator.generate(
                diagram = diagram(pkg),
                outputDir = out,
                options = mapOf("namespace" to "global", "namespaceStyle" to "flat"),
            )
            val content = File(out, "Entity.hpp").readText()
            content shouldContain "namespace domain {"
            content shouldNotContain "namespace global {"
        }
    })
