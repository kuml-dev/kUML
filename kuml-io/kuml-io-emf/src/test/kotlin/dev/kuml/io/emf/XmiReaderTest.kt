package dev.kuml.io.emf

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.eclipse.uml2.uml.Class as EmfClass
import org.eclipse.uml2.uml.Enumeration as EmfEnumeration
import org.eclipse.uml2.uml.Interface as EmfInterface

class XmiReaderTest :
    FunSpec({

        val reader = XmiReader()

        beforeSpec { EmfBootstrap.init() }

        test("readFromString liest einfaches XMI mit einer Klasse") {
            val xmi =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <uml:Model xmi:version="20131001"
                           xmlns:xmi="http://www.omg.org/spec/XMI/20131001"
                           xmlns:uml="http://www.eclipse.org/uml2/5.0.0/UML"
                           xmi:id="_m" name="TestModel">
                  <packagedElement xmi:type="uml:Class" xmi:id="_c1" name="Customer"/>
                </uml:Model>
                """.trimIndent()
            val model = reader.readFromString(xmi)
            model shouldNotBe null
            model.name shouldBe "TestModel"
            val classes = model.packagedElements.filterIsInstance<EmfClass>()
            classes.size shouldBe 1
            classes.first().name shouldBe "Customer"
        }

        test("readFromString liest XMI mit Interface und Enumeration") {
            val xmi =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <uml:Model xmi:version="20131001"
                           xmlns:xmi="http://www.omg.org/spec/XMI/20131001"
                           xmlns:uml="http://www.eclipse.org/uml2/5.0.0/UML"
                           xmi:id="_m" name="DomainModel">
                  <packagedElement xmi:type="uml:Interface" xmi:id="_i1" name="Printable"/>
                  <packagedElement xmi:type="uml:Enumeration" xmi:id="_e1" name="Status">
                    <ownedLiteral xmi:type="uml:EnumerationLiteral" xmi:id="_l1" name="ACTIVE"/>
                  </packagedElement>
                </uml:Model>
                """.trimIndent()
            val model = reader.readFromString(xmi)
            val interfaces = model.packagedElements.filterIsInstance<EmfInterface>()
            val enums = model.packagedElements.filterIsInstance<EmfEnumeration>()
            interfaces.size shouldBe 1
            interfaces.first().name shouldBe "Printable"
            enums.size shouldBe 1
            enums.first().name shouldBe "Status"
        }

        test("read liest ea-minimal.xmi — 2 Klassen gefunden") {
            val file =
                checkNotNull(
                    XmiReaderTest::class.java.classLoader.getResource("xmi-samples/ea-minimal.xmi"),
                ) { "ea-minimal.xmi not found on classpath" }
            val model = reader.read(java.io.File(file.toURI()))
            val classes = model.packagedElements.filterIsInstance<EmfClass>()
            classes.size shouldBe 2
            classes.map { it.name }.toSet() shouldBe setOf("Order", "Item")
        }

        test("read liest papyrus-minimal.xmi — Klasse + Interface + Enumeration mit 2 Literals") {
            val file =
                checkNotNull(
                    XmiReaderTest::class.java.classLoader.getResource("xmi-samples/papyrus-minimal.xmi"),
                ) { "papyrus-minimal.xmi not found on classpath" }
            val model = reader.read(java.io.File(file.toURI()))
            val classes = model.packagedElements.filterIsInstance<EmfClass>()
            val interfaces = model.packagedElements.filterIsInstance<EmfInterface>()
            val enums = model.packagedElements.filterIsInstance<EmfEnumeration>()
            classes.size shouldBe 1
            interfaces.size shouldBe 1
            enums.size shouldBe 1
            enums.first().ownedLiterals.size shouldBe 2
        }

        test("Fehlerfall: kein UML Model im XMI wirft Exception") {
            // Ein XMI ohne uml:Model-Root löst entweder einen Lade-Fehler oder
            // unsere IllegalStateException aus (je nachdem ob das Namespace-Prefix
            // bekannt ist). Beide Fälle gelten als korrekt.
            val xmi =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <uml:Package xmi:version="20131001"
                             xmlns:xmi="http://www.omg.org/spec/XMI/20131001"
                             xmlns:uml="http://www.eclipse.org/uml2/5.0.0/UML"
                             xmi:id="_pkg" name="NotAModel"/>
                """.trimIndent()
            shouldThrow<IllegalStateException> {
                reader.readFromString(xmi)
            }
        }
    })
