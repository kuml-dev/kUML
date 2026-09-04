package dev.kuml.desktop.ai

import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.c4.dsl.c4Model
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Model
import dev.kuml.core.dsl.layout.layout
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

class ScriptSerializerTest :
    FunSpec({

        test("emptyUml produces a classDiagram block without extra class names") {
            val model = AnyKumlModel.emptyUml("MyModel")
            val dsl = ScriptSerializer.toDsl(model)
            dsl shouldContain "classDiagram"
            dsl shouldNotContain "UmlClass"
            dsl shouldNotBe ""
        }

        test("empty C4 model produces a c4Model block without extra element names") {
            val model = AnyKumlModel.emptyC4()
            val dsl = ScriptSerializer.toDsl(model)
            dsl shouldContain "c4Model"
            dsl shouldNotContain "TODO V3.0.26"
        }

        test("empty SysML2 model produces a sysml2Model block without extra element names") {
            val model = AnyKumlModel.emptySysml2()
            val dsl = ScriptSerializer.toDsl(model)
            dsl shouldContain "sysml2Model"
            dsl shouldNotContain "TODO V3.0.26"
        }

        test("non-trivial C4 model round-trips through the real script host") {
            val original =
                c4Model(name = "Internet Banking System", description = "Online banking for retail customers") {
                    val customer = person(name = "Customer") { description = "A customer" }
                    lateinit var webAppRef: C4Container
                    val system =
                        softwareSystem(name = "Internet Banking") {
                            description = "The main banking system"
                            webAppRef =
                                container(name = "Web Application") {
                                    technology = "React"
                                    component(name = "Auth Module") {
                                        codeElement(name = "AuthController") { technology = "Kotlin" }
                                    }
                                }
                        }
                    relationship(source = customer, target = system) { technology = "HTTPS" }
                    systemContextDiagram(name = "Context") { include(customer, system) }
                    deploymentNode(name = "AWS") {
                        layout {
                            col = 1
                            row = 1
                        }
                        containerInstance(name = "Web App Instance", containerId = webAppRef.id)
                    }
                }
            val dsl = ScriptSerializer.toDsl(AnyKumlModel.C4(model = original))

            val evalResult = KumlScriptHost.eval(code = dsl, fileName = "c4-roundtrip.kuml.kts")
            val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
            errors shouldHaveSize 0
            val success = evalResult as? ResultWithDiagnostics.Success ?: error("script evaluation failed: $evalResult")
            val returnValue = success.value.returnValue
            val reparsed = (returnValue as? ResultValue.Value)?.value as? C4Model ?: error("script did not return a C4Model")

            reparsed.description shouldBe original.description
            reparsed.elements shouldHaveSize original.elements.size
            reparsed.relationships shouldHaveSize original.relationships.size
            reparsed.diagrams shouldHaveSize original.diagrams.size
            reparsed.elements.map { it.name }.toSet() shouldBe original.elements.map { it.name }.toSet()
        }

        test("non-trivial SysML2 model round-trips through the real script host") {
            val original =
                sysml2Model(name = "HybridVehicle") {
                    val engine = partDef(name = "Engine")
                    partDef(name = "Vehicle") {
                        part(name = "engine", typeId = engine.id)
                    }
                    bdd(name = "Structural overview") {
                        includeById("Engine")
                        includeById("Vehicle")
                    }
                }
            val dsl = ScriptSerializer.toDsl(AnyKumlModel.Sysml2(model = original))

            val evalResult = KumlScriptHost.eval(code = dsl, fileName = "sysml2-roundtrip.kuml.kts")
            val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
            errors shouldHaveSize 0
            val success = evalResult as? ResultWithDiagnostics.Success ?: error("script evaluation failed: $evalResult")
            val returnValue = success.value.returnValue
            val reparsed = (returnValue as? ResultValue.Value)?.value as? Sysml2Model ?: error("script did not return a Sysml2Model")

            reparsed.definitions shouldHaveSize original.definitions.size
            reparsed.diagrams shouldHaveSize original.diagrams.size
            reparsed.definitions.map { it.id }.toSet() shouldBe original.definitions.map { it.id }.toSet()
            val reparsedVehicle = reparsed.definitions.filterIsInstance<PartDefinition>().first { it.id == "Vehicle" }
            reparsedVehicle.features shouldHaveSize 1
        }
    })
