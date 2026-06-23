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
            // Koog 1.0.0: tool names use customName (snake_case): add_person, add_software_system, etc.
            toolNames.any { it.contains("person") || it.contains("system") || it.contains("relationship") } shouldBe true
        }

        test("forSysml2 contains SysML2 tools") {
            val ctx = AgentEditingContext.emptySysml2()
            val registry = KumlToolRegistry.forSysml2(ctx)
            val toolNames = registry.tools.map { it.name }
            // Koog 1.0.0: tool names use customName (snake_case): add_part_def, add_state, add_constraint, etc.
            toolNames.any { it.contains("part") || it.contains("state") || it.contains("constraint") } shouldBe true
        }

        test("inspectionOnly contains only read-only tools") {
            val ctx = AgentEditingContext.emptyUml()
            val registry = KumlToolRegistry.inspectionOnly(ctx)
            val toolNames = registry.tools.map { it.name }
            // No editing tools — Koog 1.0.0: tool names use customName (snake_case)
            toolNames.none { it.startsWith("add_class") || it.startsWith("add_interface") || it.startsWith("remove_element") } shouldBe true
            // Has inspection tools (snake_case: list_elements, get_element_details, find_unused_elements)
            toolNames.any { it.contains("list_elements") || it.contains("get_element") || it.contains("find_unused") } shouldBe true
        }
    })
