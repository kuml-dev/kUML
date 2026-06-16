package dev.kuml.io.emf

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import org.eclipse.emf.ecore.EPackage
import org.eclipse.uml2.uml.UMLFactory
import org.eclipse.uml2.uml.UMLPackage

class EmfBootstrapTest :
    FunSpec({

        beforeEach { EmfBootstrap.resetForTest() }

        test("init() registriert UMLPackage in der globalen EPackage.Registry") {
            EmfBootstrap.init()
            val registered = EPackage.Registry.INSTANCE[UMLPackage.eNS_URI]
            registered shouldNotBe null
        }

        test("init() ist idempotent — zweimaliger Aufruf wirft keinen Fehler") {
            shouldNotThrowAny {
                EmfBootstrap.init()
                EmfBootstrap.init()
            }
        }

        test("UMLFactory ist nach init() verfügbar und createClass() wirft kein NPE") {
            EmfBootstrap.init()
            shouldNotThrowAny {
                val cls = UMLFactory.eINSTANCE.createClass()
                cls shouldNotBe null
            }
        }
    })
