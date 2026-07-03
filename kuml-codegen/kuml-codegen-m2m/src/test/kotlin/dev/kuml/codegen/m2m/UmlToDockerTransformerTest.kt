package dev.kuml.codegen.m2m

import dev.kuml.codegen.m2m.docker.UmlToDockerTransformer
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlComponent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class UmlToDockerTransformerTest :
    FunSpec(body = {

        val transformer = UmlToDockerTransformer()
        val ctx = TransformContext()

        // ── Helpers ───────────────────────────────────────────────────────────────

        fun component(
            id: String,
            name: String,
        ) = UmlComponent(id = id, name = name)

        fun diagram(vararg elements: dev.kuml.core.model.KumlElement) = KumlDiagram(name = "Test", elements = elements.toList())

        // ── Tests ─────────────────────────────────────────────────────────────────

        test("single component produces one Dockerfile with correct FROM") {
            val result = transformer.transform(diagram(component("svc", "OrderService")), ctx)
            val files = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output
            files shouldHaveSize 1
            files[0].relativePath shouldBe "OrderService/Dockerfile"
            files[0].content shouldContain "FROM eclipse-temurin:21-jre-alpine"
        }

        test("baseImage option overrides default FROM image") {
            val customCtx = TransformContext(options = mapOf("baseImage" to "amazoncorretto:17-alpine"))
            val result = transformer.transform(diagram(component("svc", "OrderService")), customCtx)
            val content = (result as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldContain "FROM amazoncorretto:17-alpine"
        }

        test("port option changes EXPOSE line") {
            val customCtx = TransformContext(options = mapOf("port" to "9090"))
            val result = transformer.transform(diagram(component("svc", "OrderService")), customCtx)
            val content = (result as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldContain "EXPOSE 9090"
        }

        test("component name appears in COPY line as kebab-case") {
            val result = transformer.transform(diagram(component("svc", "OrderService")), ctx)
            val content = (result as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldContain "COPY build/libs/order-service*.jar app.jar"
        }

        test("buildDir option changes COPY source directory") {
            val customCtx = TransformContext(options = mapOf("buildDir" to "target"))
            val result = transformer.transform(diagram(component("svc", "OrderService")), customCtx)
            val content = (result as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldContain "COPY target/order-service*.jar app.jar"
        }

        test("two-component diagram produces two Dockerfiles") {
            val result =
                transformer.transform(
                    diagram(
                        component("svc1", "OrderService"),
                        component("svc2", "PaymentService"),
                    ),
                    ctx,
                )
            val files = (result as TransformResult.Success<List<GeneratedFile>>).output
            files shouldHaveSize 2
            val paths = files.map { it.relativePath }.toSet()
            paths shouldBe setOf("OrderService/Dockerfile", "PaymentService/Dockerfile")
        }

        test("list-transformers shows uml-to-docker") {
            TransformerRegistry.clear()
            TransformerRegistry.loadFromClasspath()
            val ids = TransformerRegistry.ids()
            ids shouldContain "uml-to-docker"
            TransformerRegistry.clear()
        }

        test("generated comment includes component name") {
            val result = transformer.transform(diagram(component("svc", "OrderService")), ctx)
            val content = (result as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldContain "# Component: OrderService"
        }
    })
