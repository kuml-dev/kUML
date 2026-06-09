package dev.kuml.codegen.m2m.k8s

import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.KumlTransformer
import dev.kuml.codegen.m2m.KumlTransformerProvider
import dev.kuml.codegen.m2m.TraceabilityLink
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.TransformTrace
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlComponent

/**
 * Transforms a UML component diagram into Kubernetes Deployment + Service manifests.
 *
 * For each [UmlComponent] in the diagram:
 * - Emits one YAML file at `<component-name-kebab>/deployment.yaml`
 * - The file contains a Kubernetes `Deployment` and a `Service` separated by `---`
 *
 * Name conversion: PascalCase / camelCase → kebab-case
 * (e.g. `OrderService` → `order-service`).
 *
 * Options (via [TransformContext.options]):
 * - `"namespace"` — Kubernetes namespace, default `"default"`
 * - `"imageRegistry"` — image registry prefix (e.g. `"registry.example.com/"`), default `""`
 * - `"replicas"` — default replica count, default `"1"`
 * - `"port"` — container port, default `"8080"`
 *
 * V2.x deferred: resource requests/limits, liveness/readiness probes,
 * ConfigMap / Secret volumes, Ingress generation, HPA.
 */
public class UmlToK8sTransformer : KumlTransformer<KumlDiagram, List<GeneratedFile>> {
    override val id: String = "uml-to-k8s"
    override val description: String =
        "UML component diagram → Kubernetes Deployment + Service manifests (one file per component)"

    override fun transform(
        source: KumlDiagram,
        ctx: TransformContext,
    ): TransformResult<List<GeneratedFile>> {
        val namespace = ctx.options["namespace"] ?: DEFAULT_NAMESPACE
        val imageRegistry = ctx.options["imageRegistry"] ?: DEFAULT_IMAGE_REGISTRY
        val replicas = ctx.options["replicas"] ?: DEFAULT_REPLICAS
        val port = ctx.options["port"] ?: DEFAULT_PORT

        val components = source.elements.filterIsInstance<UmlComponent>()
        var trace = TransformTrace()
        val files = mutableListOf<GeneratedFile>()

        for (component in components) {
            val kebabName = camelCaseToKebab(component.name)
            val imagePrefix =
                if (imageRegistry.isNotEmpty() && !imageRegistry.endsWith("/")) {
                    "$imageRegistry/"
                } else {
                    imageRegistry
                }
            val content =
                buildManifest(
                    name = kebabName,
                    namespace = namespace,
                    image = "$imagePrefix$kebabName:latest",
                    replicas = replicas,
                    port = port,
                )
            val relativePath = "$kebabName/deployment.yaml"
            files += GeneratedFile(relativePath, content)
            trace = trace.plus(TraceabilityLink(component.id, relativePath, RULE_COMPONENT_TO_K8S))
        }

        return TransformResult.Success(files, trace)
    }

    // ── Manifest generation ───────────────────────────────────────────────────

    private fun buildManifest(
        name: String,
        namespace: String,
        image: String,
        replicas: String,
        port: String,
    ): String {
        val sb = StringBuilder()

        // Deployment
        sb.appendLine("apiVersion: apps/v1")
        sb.appendLine("kind: Deployment")
        sb.appendLine("metadata:")
        sb.appendLine("  name: $name")
        if (namespace != "default") {
            sb.appendLine("  namespace: $namespace")
        }
        sb.appendLine("  labels:")
        sb.appendLine("    app: $name")
        sb.appendLine("spec:")
        sb.appendLine("  replicas: $replicas")
        sb.appendLine("  selector:")
        sb.appendLine("    matchLabels:")
        sb.appendLine("      app: $name")
        sb.appendLine("  template:")
        sb.appendLine("    metadata:")
        sb.appendLine("      labels:")
        sb.appendLine("        app: $name")
        sb.appendLine("    spec:")
        sb.appendLine("      containers:")
        sb.appendLine("        - name: $name")
        sb.appendLine("          image: $image")
        sb.appendLine("          ports:")
        sb.appendLine("            - containerPort: $port")

        // Separator
        sb.appendLine("---")

        // Service
        sb.appendLine("apiVersion: v1")
        sb.appendLine("kind: Service")
        sb.appendLine("metadata:")
        sb.appendLine("  name: $name")
        if (namespace != "default") {
            sb.appendLine("  namespace: $namespace")
        }
        sb.appendLine("spec:")
        sb.appendLine("  selector:")
        sb.appendLine("    app: $name")
        sb.appendLine("  ports:")
        sb.appendLine("    - protocol: TCP")
        sb.appendLine("      port: 80")
        sb.append("      targetPort: $port")

        return sb.toString().trimEnd() + "\n"
    }

    // ── Name conversion ───────────────────────────────────────────────────────

    /**
     * Converts a PascalCase or camelCase name to kebab-case.
     * Examples: `OrderService` → `order-service`, `myService` → `my-service`
     */
    internal fun camelCaseToKebab(name: String): String =
        buildString {
            for ((i, ch) in name.withIndex()) {
                if (ch.isUpperCase() && i > 0) append('-')
                append(ch.lowercaseChar())
            }
        }

    private companion object {
        const val DEFAULT_NAMESPACE = "default"
        const val DEFAULT_IMAGE_REGISTRY = ""
        const val DEFAULT_REPLICAS = "1"
        const val DEFAULT_PORT = "8080"
        const val RULE_COMPONENT_TO_K8S = "uml-component-to-k8s-deployment"
    }
}

/** ServiceLoader provider for [UmlToK8sTransformer]. */
public class UmlToK8sTransformerProvider : KumlTransformerProvider {
    override fun transformer(): UmlToK8sTransformer = UmlToK8sTransformer()
}
