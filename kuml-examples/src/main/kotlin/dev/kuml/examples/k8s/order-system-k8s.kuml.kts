@file:Suppress("unused")

import dev.kuml.core.dsl.componentDiagram
import dev.kuml.uml.dsl.component
import dev.kuml.uml.dsl.port

/**
 * Order System K8s — UML component diagram for Kubernetes manifest generation (V2.0.23).
 *
 * Uses the same Order System architecture model (OrderService, PaymentService,
 * InvoiceService, MessageBroker) and targets the `uml-to-k8s` M2M transformer
 * to produce Kubernetes Deployment + Service YAML manifests.
 *
 * Run:
 * ```
 * kuml transform order-system-k8s.kuml.kts --transformer uml-to-k8s --output build/k8s-gen/
 * ```
 *
 * Produces four manifest files:
 *   order-service/deployment.yaml
 *   payment-service/deployment.yaml
 *   invoice-service/deployment.yaml
 *   message-broker/deployment.yaml
 *
 * Each file contains a Kubernetes Deployment + Service pair.
 *
 * With namespace + registry override:
 * ```
 * kuml transform order-system-k8s.kuml.kts \
 *   --transformer uml-to-k8s \
 *   --option namespace=production \
 *   --option imageRegistry=registry.example.com \
 *   --option replicas=2 \
 *   --output build/k8s-gen/
 * ```
 */
componentDiagram(name = "Order System — K8s") {

    component(name = "OrderService") {
        stereotypes += "service"
        port(name = "api")
        port(name = "events")
    }

    component(name = "PaymentService") {
        stereotypes += "service"
        port(name = "api")
    }

    component(name = "InvoiceService") {
        port(name = "orderEvents")
    }

    component(name = "MessageBroker") {
        port(name = "pub")
        port(name = "sub")
    }
}

// Run: kuml transform order-system-k8s.kuml.kts --transformer uml-to-k8s --output build/k8s-gen/
