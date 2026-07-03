package dev.kuml.codegen.m2m

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.codegen.m2m.c4.C4ToUmlTransformer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf

class C4ToUmlTransformerTest :
    FunSpec(body = {

        val transformer = C4ToUmlTransformer()
        val ctx = TransformContext()

        // ── Helpers ───────────────────────────────────────────────────────────────

        fun emptyModel(name: String = "Test Model") = C4Model(id = "model-1", name = name)

        fun modelWith(
            name: String = "Test Model",
            elements: List<dev.kuml.c4.model.C4Element> = emptyList(),
            relationships: List<C4Relationship> = emptyList(),
        ) = C4Model(
            id = "model-1",
            name = name,
            elements = elements,
            relationships = relationships,
        )

        fun system(
            id: String,
            name: String,
            external: Boolean = false,
            containerIds: List<String> = emptyList(),
        ) = C4SoftwareSystem(id = id, name = name, external = external, containers = containerIds)

        fun container(
            id: String,
            name: String,
            systemId: String? = null,
            technology: String? = null,
        ) = C4Container(id = id, name = name, system = systemId, technology = technology)

        fun component(
            id: String,
            name: String,
            containerId: String? = null,
            technology: String? = null,
        ) = C4Component(id = id, name = name, container = containerId, technology = technology)

        fun person(
            id: String,
            name: String,
            external: Boolean = false,
        ) = C4Person(id = id, name = name, external = external)

        fun relationship(
            id: String,
            source: String,
            target: String,
            label: String = "uses",
        ) = C4Relationship(id = id, source = source, target = target, label = label)

        // ── Tests ─────────────────────────────────────────────────────────────────

        test("single container produces one classOf with stereotype container") {
            val web = container("web", "Web Application", technology = null)
            val sys = system("sys1", "Internet Banking System", containerIds = listOf("web"))
            val model = modelWith(elements = listOf(sys, web))
            val result = transformer.transform(model, ctx)

            val content = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output[0].content
            content shouldContain """classOf(name = "WebApplication")"""
            content shouldContain """stereotypes += "container""""
        }

        test("two containers with relationship produce dependency in output") {
            val web = container("web", "Web Application", systemId = "sys1")
            val api = container("api", "API Application", systemId = "sys1")
            val sys = system("sys1", "Banking", containerIds = listOf("web", "api"))
            val rel = relationship("r1", "web", "api")
            val model = modelWith(elements = listOf(sys, web, api), relationships = listOf(rel))
            val result = transformer.transform(model, ctx)

            val content = (result as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldContain "dependency(source = WebApplication, target = APIApplication)"
        }

        test("name conversion: Web Application becomes WebApplication") {
            transformer.toPascalCase("Web Application") shouldBe "WebApplication"
        }

        test("name conversion: API Server becomes APIServer") {
            transformer.toPascalCase("API Server") shouldBe "APIServer"
        }

        test("technology attribute added when non-null") {
            val web = container("web", "Web Application", technology = "React / JavaScript")
            val sys = system("sys1", "Banking", containerIds = listOf("web"))
            val model = modelWith(elements = listOf(sys, web))
            val content = (transformer.transform(model, ctx) as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldContain """attribute(name = "technology", type = "React / JavaScript")"""
        }

        test("external system gets both system and external stereotypes") {
            val ext = system("ext1", "Email Service", external = true)
            val model = modelWith(elements = listOf(ext))
            val content = (transformer.transform(model, ctx) as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldContain """stereotypes += "system""""
            content shouldContain """stereotypes += "external""""
        }

        test("person excluded by default") {
            val user = person("p1", "Customer")
            val sys = system("sys1", "Banking")
            val model = modelWith(elements = listOf(sys, user))
            val content = (transformer.transform(model, ctx) as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldNotContain """classOf(name = "Customer")"""
        }

        test("person included when includePersons=true") {
            val user = person("p1", "Customer")
            val sys = system("sys1", "Banking")
            val model = modelWith(elements = listOf(sys, user))
            val personCtx = TransformContext(options = mapOf("includePersons" to "true"))
            val content = (transformer.transform(model, personCtx) as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldContain """classOf(name = "Customer")"""
            content shouldContain """stereotypes += "actor""""
        }

        test("empty model with no systems produces minimal valid script") {
            val result = transformer.transform(emptyModel("Empty"), ctx)
            val files = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output
            files shouldHaveSize 1
            files[0].relativePath shouldBe "uml-from-c4.kuml.kts"
            val content = files[0].content
            content shouldContain "classDiagram"
            content shouldNotContain "classOf"
        }

        test("list-transformers shows c4-to-uml via ServiceLoader") {
            TransformerRegistry.clear()
            TransformerRegistry.loadFromClasspath()
            val ids = TransformerRegistry.ids()
            ids shouldContain "c4-to-uml"
            TransformerRegistry.clear()
        }

        test("generated script begins with Generated by kUML comment") {
            val model = emptyModel("My Model")
            val content = (transformer.transform(model, ctx) as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldStartWith "// Generated by kUML C4→UML transformer"
        }

        test("multiple containers preserve insertion order") {
            val c1 = container("c1", "Alpha Service", systemId = "sys1")
            val c2 = container("c2", "Beta Service", systemId = "sys1")
            val c3 = container("c3", "Gamma Service", systemId = "sys1")
            val sys = system("sys1", "Banking", containerIds = listOf("c1", "c2", "c3"))
            val model = modelWith(elements = listOf(sys, c1, c2, c3))
            val content = (transformer.transform(model, ctx) as TransformResult.Success<List<GeneratedFile>>).output[0].content
            val idx1 = content.indexOf("AlphaService")
            val idx2 = content.indexOf("BetaService")
            val idx3 = content.indexOf("GammaService")
            assert(idx1 < idx2) { "AlphaService should appear before BetaService" }
            assert(idx2 < idx3) { "BetaService should appear before GammaService" }
        }

        test("relationship to unknown element is silently skipped") {
            val web = container("web", "Web Application", systemId = "sys1")
            val sys = system("sys1", "Banking", containerIds = listOf("web"))
            val rel = relationship("r1", "web", "unknown-target")
            val model = modelWith(elements = listOf(sys, web), relationships = listOf(rel))
            val content = (transformer.transform(model, ctx) as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldNotContain "dependency"
        }

        test("diagramName option overrides default name") {
            val model = emptyModel("Internet Banking System")
            val customCtx = TransformContext(options = mapOf("diagramName" to "My Custom Diagram"))
            val content = (transformer.transform(model, customCtx) as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldContain """classDiagram(name = "My Custom Diagram")"""
            content shouldNotContain "Internet Banking System — UML"
        }
    })
