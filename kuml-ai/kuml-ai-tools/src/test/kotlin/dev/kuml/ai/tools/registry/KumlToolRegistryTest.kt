package dev.kuml.ai.tools.registry

import dev.kuml.ai.tools.context.AgentEditingContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KumlToolRegistryTest :
    FunSpec({

        test("forUml includes UML Rendering and Inspection tool sets") {
            val ctx = AgentEditingContext.emptyUml()
            val registry = KumlToolRegistry.forUml(ctx)
            val toolNames = registry.tools.map { it.name }
            // Should have UML tools (addClass, addInterface, etc. in camelCase)
            toolNames.any { it.startsWith("add") } shouldBe true
            // Should have render tools
            toolNames.any { it.contains("render") || it.contains("validate") || it.contains("Preview") || it.contains("Validate") } shouldBe
                true
            // Should have inspection tools
            toolNames.any { it.contains("list") || it.contains("List") || it.contains("element") || it.contains("Element") } shouldBe true
        }

        test("forC4 contains C4 tools") {
            val ctx = AgentEditingContext.emptyC4()
            val registry = KumlToolRegistry.forC4(ctx)
            val toolNames = registry.tools.map { it.name }
            // Tool names are Kotlin method names: addPerson, addSoftwareSystem, etc.
            toolNames.any { it.contains("Person") || it.contains("System") || it.contains("Relationship") } shouldBe true
        }

        test("forSysml2 contains SysML2 tools") {
            val ctx = AgentEditingContext.emptySysml2()
            val registry = KumlToolRegistry.forSysml2(ctx)
            val toolNames = registry.tools.map { it.name }
            // Tool names are Kotlin method names: addPartDef, addState, addConstraint, etc.
            toolNames.any { it.contains("PartDef") || it.contains("State") || it.contains("Constraint") } shouldBe true
        }

        test("inspectionOnly contains only read-only tools") {
            val ctx = AgentEditingContext.emptyUml()
            val registry = KumlToolRegistry.inspectionOnly(ctx)
            val toolNames = registry.tools.map { it.name }
            // No editing tools (camelCase Kotlin method names)
            toolNames.none { it.startsWith("addClass") || it.startsWith("addInterface") || it.startsWith("removeElement") } shouldBe true
            // Has inspection tools
            toolNames.any { it.contains("listElements") || it.contains("getElement") || it.contains("findUnused") } shouldBe true
        }
    })
