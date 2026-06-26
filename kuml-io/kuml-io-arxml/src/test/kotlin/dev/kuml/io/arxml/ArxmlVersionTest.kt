package dev.kuml.io.arxml

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.jdom2.Element
import org.jdom2.Namespace

class ArxmlVersionTest :
    FunSpec({

        test("fromNamespace resolves r4.0 namespace to first matching entry") {
            val result = ArxmlVersion.fromNamespace("http://autosar.org/schema/r4.0")
            result.shouldNotBeNull()
        }

        test("fromNamespace returns null for unknown namespace") {
            val result = ArxmlVersion.fromNamespace("http://unknown.org/schema")
            result.shouldBeNull()
        }

        test("detect distinguishes R22_11 via schemaLocation token") {
            val ns = Namespace.getNamespace("http://autosar.org/schema/r4.0")
            val xsiNs = Namespace.getNamespace("xsi", "http://www.w3.org/2001/XMLSchema-instance")
            val root = Element("AUTOSAR", ns)
            root.setAttribute("schemaLocation", "http://autosar.org/schema/r4.0 AUTOSAR_00051.xsd", xsiNs)

            val result = ArxmlVersion.detect(root)
            result shouldBe ArxmlVersion.R22_11
        }

        test("detect distinguishes R23_11 via schemaLocation token") {
            val ns = Namespace.getNamespace("http://autosar.org/schema/r4.0")
            val xsiNs = Namespace.getNamespace("xsi", "http://www.w3.org/2001/XMLSchema-instance")
            val root = Element("AUTOSAR", ns)
            root.setAttribute("schemaLocation", "http://autosar.org/schema/r4.0 AUTOSAR_00052.xsd", xsiNs)

            val result = ArxmlVersion.detect(root)
            result shouldBe ArxmlVersion.R23_11
        }

        test("detect returns null for unknown namespace") {
            val ns = Namespace.getNamespace("http://unknown.org/schema")
            val root = Element("AUTOSAR", ns)
            val result = ArxmlVersion.detect(root)
            result.shouldBeNull()
        }

        test("all five enum entries expose non-blank namespaceUri and schemaLabel") {
            for (version in ArxmlVersion.entries) {
                version.namespaceUri.shouldNotBeBlank()
                version.schemaLabel.shouldNotBeBlank()
            }
        }
    })
