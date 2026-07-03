package dev.kuml.codegen.m2m

import dev.kuml.codegen.m2m.k8s.UmlToK8sTransformer
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlComponent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf

class UmlToK8sTransformerTest :
    FunSpec(body = {

        val transformer = UmlToK8sTransformer()
        val ctx = TransformContext()

        // ── Helpers ───────────────────────────────────────────────────────────────

        fun component(
            id: String,
            name: String,
        ) = UmlComponent(id = id, name = name)

        fun diagram(vararg elements: dev.kuml.core.model.KumlElement) = KumlDiagram(name = "Test", elements = elements.toList())

        // ── Tests ─────────────────────────────────────────────────────────────────

        test("single component produces one deployment.yaml file") {
            val result = transformer.transform(diagram(component("svc", "OrderService")), ctx)
            val files = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output
            files shouldHaveSize 1
            files[0].relativePath shouldBe "order-service/deployment.yaml"
        }

        test("component name is converted to kebab-case in Deployment metadata") {
            val result = transformer.transform(diagram(component("svc", "OrderService")), ctx)
            val content = (result as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldContain "  name: order-service"
            content shouldContain "    app: order-service"
        }

        test("replicas option overrides default value of 1") {
            val customCtx = TransformContext(options = mapOf("replicas" to "3"))
            val result = transformer.transform(diagram(component("svc", "OrderService")), customCtx)
            val content = (result as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldContain "  replicas: 3"
        }

        test("namespace option appears in Deployment and Service metadata") {
            val customCtx = TransformContext(options = mapOf("namespace" to "production"))
            val result = transformer.transform(diagram(component("svc", "PaymentService")), customCtx)
            val content = (result as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldContain "  namespace: production"
        }

        test("imageRegistry option prefixes the container image name") {
            val customCtx = TransformContext(options = mapOf("imageRegistry" to "registry.example.com"))
            val result = transformer.transform(diagram(component("svc", "OrderService")), customCtx)
            val content = (result as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldContain "          image: registry.example.com/order-service:latest"
        }

        test("containerPort reflects port option") {
            val customCtx = TransformContext(options = mapOf("port" to "9090"))
            val result = transformer.transform(diagram(component("svc", "OrderService")), customCtx)
            val content = (result as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldContain "            - containerPort: 9090"
            content shouldContain "      targetPort: 9090"
        }

        test("Service selector matches Deployment labels") {
            val result = transformer.transform(diagram(component("svc", "OrderService")), ctx)
            val content = (result as TransformResult.Success<List<GeneratedFile>>).output[0].content
            // Both Deployment matchLabels and Service selector share the same app label
            content shouldContain "      app: order-service"
            // Service selector block
            content shouldContain "  selector:\n    app: order-service"
        }

        test("list-transformers shows uml-to-k8s") {
            TransformerRegistry.clear()
            TransformerRegistry.loadFromClasspath()
            val ids = TransformerRegistry.ids()
            ids shouldContain "uml-to-k8s"
            TransformerRegistry.clear()
        }

        test("two-component diagram produces two separate files") {
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
            paths shouldBe setOf("order-service/deployment.yaml", "payment-service/deployment.yaml")
        }

        test("empty diagram produces no files") {
            val result = transformer.transform(KumlDiagram(name = "Empty", elements = emptyList()), ctx)
            val files = (result as TransformResult.Success<List<GeneratedFile>>).output
            files shouldHaveSize 0
        }

        test("camelCaseToKebab converts OrderService to order-service") {
            transformer.camelCaseToKebab("OrderService") shouldBe "order-service"
        }

        test("generated YAML starts with apiVersion apps/v1") {
            val result = transformer.transform(diagram(component("svc", "MyService")), ctx)
            val content = (result as TransformResult.Success<List<GeneratedFile>>).output[0].content
            content shouldStartWith "apiVersion: apps/v1"
        }
    })
