package dev.kuml.io.emf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.eclipse.uml2.uml.UMLFactory

class XmiToolFilterTest :
    FunSpec({

        beforeSpec { EmfBootstrap.init() }

        fun model(name: String) = UMLFactory.eINSTANCE.createModel().also { it.name = name }

        test("EA-Präfix EA_Model_ wird entfernt") {
            val m = model("EA_Model_OrderDomain")
            XmiToolFilter.normalize(m)
            m.name shouldBe "OrderDomain"
        }

        test("Visual Paradigm Versions-Präfix VP12. wird entfernt") {
            val m = model("VP12.MyModel")
            XmiToolFilter.normalize(m)
            m.name shouldBe "MyModel"
        }

        test("Normales Modell bleibt unverändert") {
            val m = model("OrderDomain")
            XmiToolFilter.normalize(m)
            m.name shouldBe "OrderDomain"
        }
    })
