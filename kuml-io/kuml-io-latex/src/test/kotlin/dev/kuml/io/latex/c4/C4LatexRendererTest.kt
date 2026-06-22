package dev.kuml.io.latex.c4

import dev.kuml.c4.model.C4CodeElement
import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4DeploymentNode
import dev.kuml.c4.model.C4Interaction
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.ComponentDiagram
import dev.kuml.c4.model.ContainerDiagram
import dev.kuml.c4.model.DeploymentDiagram
import dev.kuml.c4.model.DynamicDiagram
import dev.kuml.c4.model.SystemContextDiagram
import dev.kuml.c4.model.SystemLandscapeDiagram
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.SampleOutput
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

/**
 * End-to-end behaviour for the C4 LaTeX renderer — exercises all C4 element
 * types and confirms that labels, stereotypes, and TikZ structure are correct.
 *
 * Strategy:
 *  1. **Structural assertions** — every node uses the right TikZ style, the
 *     right stereotype text appears, and the label is LaTeX-escaped.
 *  2. **Standalone mode** — `\begin{document}` wrapper present.
 *  3. **Determinism** — byte-identical output for identical input.
 *  4. **LaTeX escaping** — special chars survive into the TikZ source safely.
 *  5. **Edge rendering** — C4 relationships produce a `\draw` path.
 */
class C4LatexRendererTest :
    StringSpec({

        // ─── Helpers ─────────────────────────────────────────────────────────

        fun singleNodeLayout(
            nodeId: String,
            x: Float = 20f,
            y: Float = 20f,
            w: Float = 200f,
            h: Float = 120f,
        ) = LayoutResult(
            engineId = LayoutEngineId("test"),
            seed = 1L,
            canvas = Size(400f, 300f),
            nodes = mapOf(NodeId(nodeId) to NodeLayout(bounds = Rect(Point(x, y), Size(w, h)))),
            edges = emptyMap(),
            groups = emptyMap(),
        )

        // ─── C4 Context diagram — Person + SoftwareSystem ────────────────────

        "System Context diagram — Person renders with kuml-c4-person style and person stereotype" {
            val person = C4Person(id = "user", name = "End User", description = "A human user")
            val system = C4SoftwareSystem(id = "sys", name = "Banking System")
            val model =
                C4Model(
                    id = "m",
                    name = "M",
                    elements = listOf(person, system),
                )
            val diagram =
                SystemContextDiagram(
                    id = "ctx",
                    name = "Context",
                    elements = listOf("user"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("user")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "\\begin{tikzpicture}"
            tex shouldContain "\\end{tikzpicture}"
            tex shouldContain "kuml-c4-person"
            tex shouldContain "End User"
            tex shouldContain "\\guillemotleft{}person\\guillemotright{}"
            // Snippet mode by default.
            tex shouldNotContain "\\documentclass"

            SampleOutput.write("c4/system-context-person.tex", tex)
        }

        "System Context diagram — external SoftwareSystem gets external stereotype" {
            val externalSystem =
                C4SoftwareSystem(
                    id = "ext",
                    name = "External Payment Gateway",
                    external = true,
                )
            val model = C4Model(id = "m", name = "M", elements = listOf(externalSystem))
            val diagram =
                SystemContextDiagram(
                    id = "ctx",
                    name = "Context",
                    elements = listOf("ext"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("ext")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "kuml-c4-system"
            tex shouldContain "external software system"
            tex shouldContain "External Payment Gateway"

            SampleOutput.write("c4/external-software-system.tex", tex)
        }

        // ─── Container diagram ────────────────────────────────────────────────

        "Container diagram — Container renders with kuml-c4-container style and technology" {
            val container =
                C4Container(
                    id = "api",
                    name = "REST API",
                    technology = "Spring Boot",
                    system = "sys",
                )
            val model =
                C4Model(
                    id = "m",
                    name = "M",
                    elements = listOf(container),
                )
            val diagram =
                ContainerDiagram(
                    id = "ctr",
                    name = "Containers",
                    system = "sys",
                    elements = listOf("api"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("api")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "kuml-c4-container"
            tex shouldContain "REST API"
            tex shouldContain "container: Spring Boot"

            SampleOutput.write("c4/container-diagram.tex", tex)
        }

        // ─── Component diagram ────────────────────────────────────────────────

        "Component diagram — Component renders with kuml-c4-component style" {
            val component =
                C4Component(
                    id = "svc",
                    name = "OrderService",
                    technology = "Kotlin",
                    container = "api",
                )
            val model =
                C4Model(
                    id = "m",
                    name = "M",
                    elements = listOf(component),
                )
            val diagram =
                ComponentDiagram(
                    id = "cmp",
                    name = "Components",
                    container = "api",
                    elements = listOf("svc"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("svc")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "kuml-c4-component"
            tex shouldContain "OrderService"
            tex shouldContain "component: Kotlin"

            SampleOutput.write("c4/component-diagram.tex", tex)
        }

        // ─── Deployment diagram ───────────────────────────────────────────────

        "Deployment diagram — DeploymentNode renders with kuml-c4-node style and dashed border" {
            val node =
                C4DeploymentNode(
                    id = "k8s",
                    name = "Kubernetes Cluster",
                    technology = "AWS EKS",
                )
            val model = C4Model(id = "m", name = "M", elements = listOf(node))
            val diagram =
                DeploymentDiagram(
                    id = "dep",
                    name = "Deployment",
                    elements = listOf("k8s"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("k8s")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "kuml-c4-node"
            tex shouldContain "Kubernetes Cluster"
            tex shouldContain "node: AWS EKS"
            // Dashed border is baked into the kuml-c4-node TikZ style definition.
            tex shouldContain "dashed"

            SampleOutput.write("c4/deployment-node.tex", tex)
        }

        // ─── Standalone mode ──────────────────────────────────────────────────

        "Standalone mode wraps the C4 picture in a complete LaTeX document" {
            val person = C4Person(id = "u", name = "User")
            val model = C4Model(id = "m", name = "M", elements = listOf(person))
            val diagram =
                SystemContextDiagram(
                    id = "ctx",
                    name = "Context",
                    elements = listOf("u"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("u")

            val tex =
                KumlLatexRenderer.toLatex(
                    diagram,
                    model,
                    layout,
                    options = LatexRenderOptions(standalone = true),
                )

            tex shouldStartWith "\\documentclass[border=10pt]{standalone}"
            tex shouldContain "\\usepackage{tikz}"
            tex shouldContain "\\usepackage[utf8]{inputenc}"
            tex shouldContain "\\begin{document}"
            tex shouldContain "\\end{document}"
            tex shouldContain "kuml-c4-person"

            SampleOutput.write("c4/system-context-standalone.tex", tex)
        }

        // ─── Determinism ──────────────────────────────────────────────────────

        "Deterministic — same C4 input yields byte-identical output" {
            val person = C4Person(id = "u", name = "User")
            val system = C4SoftwareSystem(id = "sys", name = "MySystem")
            val rel = C4Relationship(id = "rel1", source = "u", target = "sys", label = "Uses")
            val model =
                C4Model(
                    id = "m",
                    name = "M",
                    elements = listOf(person, system),
                    relationships = listOf(rel),
                )
            val diagram =
                SystemContextDiagram(
                    id = "ctx",
                    name = "Context",
                    elements = listOf("u", "sys"),
                    relationships = listOf("rel1"),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 42L,
                    canvas = Size(500f, 300f),
                    nodes =
                        mapOf(
                            NodeId("u") to NodeLayout(bounds = Rect(Point(20f, 20f), Size(160f, 100f))),
                            NodeId("sys") to NodeLayout(bounds = Rect(Point(260f, 20f), Size(200f, 100f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("rel1") to
                                EdgeRoute.Direct(
                                    source = Point(180f, 70f),
                                    target = Point(260f, 70f),
                                ),
                        ),
                    groups = emptyMap(),
                )

            val one = KumlLatexRenderer.toLatex(diagram, model, layout)
            val two = KumlLatexRenderer.toLatex(diagram, model, layout)
            one shouldBe two

            SampleOutput.write("c4/determinism-check.tex", one)
        }

        // ─── LaTeX escaping ───────────────────────────────────────────────────

        "LaTeX special chars in C4 element names are escaped" {
            val system =
                C4SoftwareSystem(
                    id = "s",
                    name = "Order & Payment System",
                    description = "Handles 100% of transactions",
                )
            val model = C4Model(id = "m", name = "M", elements = listOf(system))
            val diagram =
                SystemContextDiagram(
                    id = "ctx",
                    name = "Context",
                    elements = listOf("s"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("s")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            // '&' must be escaped to '\&'
            tex shouldContain "Order \\& Payment System"
            // '%' must be escaped to '\%'
            tex shouldContain "100\\%"
            // Raw '&' or '%' must not appear in node label context.
            tex shouldNotContain "Order & Payment"

            SampleOutput.write("c4/latex-escaping.tex", tex)
        }

        // ─── Relationship edge rendering ──────────────────────────────────────

        "C4 relationship renders as a draw path with label at mid-point" {
            val user = C4Person(id = "user", name = "Customer")
            val system = C4SoftwareSystem(id = "sys", name = "E-Commerce Platform")
            val rel =
                C4Relationship(
                    id = "rel1",
                    source = "user",
                    target = "sys",
                    label = "Browses",
                    technology = "HTTPS",
                )
            val model =
                C4Model(
                    id = "m",
                    name = "M",
                    elements = listOf(user, system),
                    relationships = listOf(rel),
                )
            val diagram =
                SystemContextDiagram(
                    id = "ctx",
                    name = "Context",
                    elements = listOf("user", "sys"),
                    relationships = listOf("rel1"),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(500f, 200f),
                    nodes =
                        mapOf(
                            NodeId("user") to NodeLayout(bounds = Rect(Point(20f, 40f), Size(160f, 100f))),
                            NodeId("sys") to NodeLayout(bounds = Rect(Point(300f, 40f), Size(180f, 100f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("rel1") to
                                EdgeRoute.Direct(
                                    source = Point(180f, 90f),
                                    target = Point(300f, 90f),
                                ),
                        ),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            // An edge draw command must be present.
            tex shouldContain "\\draw["
            // The relationship label must appear in the output.
            tex shouldContain "Browses"
            // The technology must be appended in "[technology]" format.
            tex shouldContain "HTTPS"
            // The combined label format "Browses [HTTPS]" must be present.
            tex shouldContain "Browses [HTTPS]"

            SampleOutput.write("c4/relationship-edge.tex", tex)
        }

        // ─── Bidirectional relationship ────────────────────────────────────────

        "Bidirectional C4 relationship uses dual-arrowhead TikZ style" {
            val clientApp = C4SoftwareSystem(id = "client", name = "Client App")
            val server = C4SoftwareSystem(id = "server", name = "API Server")
            val rel =
                C4Relationship(
                    id = "bidi1",
                    source = "client",
                    target = "server",
                    label = "Syncs",
                    technology = "WebSocket",
                    bidirectional = true,
                )
            val model =
                C4Model(
                    id = "m",
                    name = "M",
                    elements = listOf(clientApp, server),
                    relationships = listOf(rel),
                )
            val diagram =
                SystemContextDiagram(
                    id = "ctx",
                    name = "Context",
                    elements = listOf("client", "server"),
                    relationships = listOf("bidi1"),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(500f, 200f),
                    nodes =
                        mapOf(
                            NodeId("client") to NodeLayout(bounds = Rect(Point(20f, 40f), Size(160f, 100f))),
                            NodeId("server") to NodeLayout(bounds = Rect(Point(300f, 40f), Size(180f, 100f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("bidi1") to
                                EdgeRoute.Direct(
                                    source = Point(180f, 90f),
                                    target = Point(300f, 90f),
                                ),
                        ),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            // A draw command must be present.
            tex shouldContain "\\draw["
            // Bidirectional edges must use the dual-arrowhead style.
            tex shouldContain "kuml-edge-bidi"
            // The unidirectional plain style must NOT be used for this edge.
            tex shouldNotContain "kuml-edge-plain]"
            // The label must still appear.
            tex shouldContain "Syncs [WebSocket]"

            SampleOutput.write("c4/relationship-edge-bidi.tex", tex)
        }

        // ─── C4CodeElement ─────────────────────────────────────────────────────

        "C4CodeElement renders with kuml-c4-code style and code element stereotype" {
            val codeEl =
                C4CodeElement(
                    id = "orderSvcClass",
                    name = "OrderService",
                    technology = "Kotlin",
                    description = "Handles order lifecycle",
                    component = "orderComp",
                )
            val model = C4Model(id = "m", name = "M", elements = listOf(codeEl))
            val diagram =
                ComponentDiagram(
                    id = "cmp",
                    name = "Components",
                    container = "api",
                    elements = listOf("orderSvcClass"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("orderSvcClass")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "kuml-c4-code"
            tex shouldContain "OrderService"
            // Stereotype must include technology when present.
            tex shouldContain "code element: Kotlin"
            // Description must appear in the node body.
            tex shouldContain "Handles order lifecycle"

            SampleOutput.write("c4/code-element.tex", tex)
        }

        // ─── External C4Person stereotype ────────────────────────────────────

        "System Context diagram — external Person gets external person stereotype" {
            val externalPerson =
                C4Person(
                    id = "ext-user",
                    name = "External Auditor",
                    external = true,
                )
            val model = C4Model(id = "m", name = "M", elements = listOf(externalPerson))
            val diagram =
                SystemContextDiagram(
                    id = "ctx",
                    name = "Context",
                    elements = listOf("ext-user"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("ext-user")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "kuml-c4-person"
            tex shouldContain "External Auditor"
            tex shouldContain "external person"
            tex shouldNotContain "\\guillemotleft{}person\\guillemotright{}"

            SampleOutput.write("c4/external-person.tex", tex)
        }

        // ─── Technology-absent Container/Component stereotypes ────────────────

        "Container diagram — Container without technology uses plain container stereotype" {
            val container =
                C4Container(
                    id = "db",
                    name = "Database",
                    technology = null,
                    system = "sys",
                )
            val model = C4Model(id = "m", name = "M", elements = listOf(container))
            val diagram =
                ContainerDiagram(
                    id = "ctr",
                    name = "Containers",
                    system = "sys",
                    elements = listOf("db"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("db")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "kuml-c4-container"
            tex shouldContain "Database"
            tex shouldContain "\\guillemotleft{}container\\guillemotright{}"
            tex shouldNotContain "container:"

            SampleOutput.write("c4/container-no-technology.tex", tex)
        }

        "Component diagram — Component without technology uses plain component stereotype" {
            val component =
                C4Component(
                    id = "repo",
                    name = "OrderRepository",
                    technology = null,
                    container = "api",
                )
            val model = C4Model(id = "m", name = "M", elements = listOf(component))
            val diagram =
                ComponentDiagram(
                    id = "cmp",
                    name = "Components",
                    container = "api",
                    elements = listOf("repo"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("repo")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "kuml-c4-component"
            tex shouldContain "OrderRepository"
            tex shouldContain "\\guillemotleft{}component\\guillemotright{}"
            tex shouldNotContain "component:"

            SampleOutput.write("c4/component-no-technology.tex", tex)
        }

        // ─── Edge label: blank label + technology only ────────────────────────

        "Relationship with blank label and technology emits [technology] without leading space" {
            val user = C4Person(id = "u", name = "User")
            val system = C4SoftwareSystem(id = "s", name = "System")
            // label="" is the non-nullable blank case; technology is set
            val rel =
                C4Relationship(
                    id = "rel1",
                    source = "u",
                    target = "s",
                    label = "",
                    technology = "gRPC",
                )
            val model =
                C4Model(
                    id = "m",
                    name = "M",
                    elements = listOf(user, system),
                    relationships = listOf(rel),
                )
            val diagram =
                SystemContextDiagram(
                    id = "ctx",
                    name = "Context",
                    elements = listOf("u", "s"),
                    relationships = listOf("rel1"),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(400f, 200f),
                    nodes =
                        mapOf(
                            NodeId("u") to NodeLayout(bounds = Rect(Point(20f, 40f), Size(120f, 80f))),
                            NodeId("s") to NodeLayout(bounds = Rect(Point(260f, 40f), Size(120f, 80f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("rel1") to
                                EdgeRoute.Direct(
                                    source = Point(140f, 80f),
                                    target = Point(260f, 80f),
                                ),
                        ),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            // Composite label must be "[gRPC]" — no leading space before the bracket.
            // The label node wraps in \small{…}, so we check the exact escapeLatex output.
            tex shouldContain "[gRPC]"
            // Verify the composite label has no " [gRPC]" form (which would mean blank label
            // was concatenated as "label [tech]" leaving a space: " [gRPC]").
            // The correct form is `\small [gRPC]` — the space after \small is the TeX space,
            // not part of the label content. We assert the raw label content is not ` [gRPC]`
            // by checking that neither "Uses [gRPC]" nor raw "  [gRPC]" appears.
            tex shouldNotContain "Uses [gRPC]"
            // An empty-label + tech relationship must not emit a label-only part before the bracket.
            // i.e. the compositeLabel must not start with a space: check via curly-brace context.
            tex shouldNotContain "\\small  [gRPC]"

            SampleOutput.write("c4/edge-blank-label-technology-only.tex", tex)
        }

        // ─── DeploymentNode with instances > 1 ───────────────────────────────

        "DeploymentNode with instances > 1 emits times-N detail line" {
            val node =
                C4DeploymentNode(
                    id = "vm",
                    name = "App Server",
                    technology = "Ubuntu 22.04",
                    instances = 3,
                )
            val model = C4Model(id = "m", name = "M", elements = listOf(node))
            val diagram =
                DeploymentDiagram(
                    id = "dep",
                    name = "Deployment",
                    elements = listOf("vm"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("vm")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "kuml-c4-node"
            tex shouldContain "App Server"
            // instances > 1 must emit the ×N detail line using LaTeX math
            tex shouldContain "\$\\times\$3"

            SampleOutput.write("c4/deployment-node-instances.tex", tex)
        }

        // ─── SystemLandscapeDiagram smoke test ────────────────────────────────

        "SystemLandscapeDiagram renders without NPE or silent skip" {
            val sys1 = C4SoftwareSystem(id = "crm", name = "CRM System")
            val sys2 = C4SoftwareSystem(id = "erp", name = "ERP System", external = true)
            val person = C4Person(id = "staff", name = "Staff Member")
            val rel =
                C4Relationship(
                    id = "r1",
                    source = "staff",
                    target = "crm",
                    label = "Uses",
                )
            val model =
                C4Model(
                    id = "m",
                    name = "Enterprise",
                    elements = listOf(sys1, sys2, person),
                    relationships = listOf(rel),
                )
            val diagram =
                SystemLandscapeDiagram(
                    id = "landscape",
                    name = "Enterprise Landscape",
                    elements = listOf("crm", "erp", "staff"),
                    relationships = listOf("r1"),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(600f, 300f),
                    nodes =
                        mapOf(
                            NodeId("crm") to NodeLayout(bounds = Rect(Point(20f, 80f), Size(160f, 100f))),
                            NodeId("erp") to NodeLayout(bounds = Rect(Point(420f, 80f), Size(160f, 100f))),
                            NodeId("staff") to NodeLayout(bounds = Rect(Point(220f, 80f), Size(160f, 100f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("r1") to
                                EdgeRoute.Direct(
                                    source = Point(380f, 130f),
                                    target = Point(420f, 130f),
                                ),
                        ),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "\\begin{tikzpicture}"
            tex shouldContain "\\end{tikzpicture}"
            tex shouldContain "CRM System"
            tex shouldContain "ERP System"
            tex shouldContain "Staff Member"
            tex shouldContain "Uses"

            SampleOutput.write("c4/system-landscape.tex", tex)
        }

        // ─── DynamicDiagram smoke test ────────────────────────────────────────

        "DynamicDiagram renders participants without NPE or silent skip" {
            val browser = C4SoftwareSystem(id = "browser", name = "Browser")
            val api = C4Container(id = "api", name = "API", system = "webapp")
            val interaction =
                C4Interaction(
                    id = "i1",
                    source = "browser",
                    target = "api",
                    description = "GET /orders",
                    sequence = 1,
                    technology = "HTTPS",
                )
            val rel =
                C4Relationship(
                    id = "rel1",
                    source = "browser",
                    target = "api",
                    label = "GET /orders",
                    technology = "HTTPS",
                )
            val model =
                C4Model(
                    id = "m",
                    name = "M",
                    elements = listOf(browser, api),
                    relationships = listOf(rel),
                )
            val diagram =
                DynamicDiagram(
                    id = "dyn",
                    name = "Order Flow",
                    interactions = listOf(interaction),
                    elements = listOf("browser", "api"),
                    relationships = listOf("rel1"),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(500f, 200f),
                    nodes =
                        mapOf(
                            NodeId("browser") to NodeLayout(bounds = Rect(Point(20f, 40f), Size(160f, 100f))),
                            NodeId("api") to NodeLayout(bounds = Rect(Point(320f, 40f), Size(160f, 100f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("rel1") to
                                EdgeRoute.Direct(
                                    source = Point(180f, 90f),
                                    target = Point(320f, 90f),
                                ),
                        ),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "\\begin{tikzpicture}"
            tex shouldContain "\\end{tikzpicture}"
            tex shouldContain "Browser"
            tex shouldContain "API"
            // The edge label from the relationship must appear
            tex shouldContain "GET /orders"

            SampleOutput.write("c4/dynamic-diagram.tex", tex)
        }

        // ─── TikZ styles preamble ─────────────────────────────────────────────

        "C4 TikZ styles are included in the picture preamble" {
            val person = C4Person(id = "u", name = "User")
            val model = C4Model(id = "m", name = "M", elements = listOf(person))
            val diagram =
                SystemContextDiagram(
                    id = "ctx",
                    name = "Context",
                    elements = listOf("u"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("u")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "kuml-c4-person/.style"
            tex shouldContain "kuml-c4-system/.style"
            tex shouldContain "kuml-c4-container/.style"
            tex shouldContain "kuml-c4-component/.style"
            tex shouldContain "kuml-c4-node/.style"
        }

        // ─── LaTeX injection: extended special chars in name/description ─────

        "LaTeX injection chars in SoftwareSystem name and description are all escaped" {
            // Covers: \  $  _  #  ^  ~  (& and % are already in the existing test)
            val system =
                C4SoftwareSystem(
                    id = "s",
                    name = "Sys\\Name_Under#Hash^Caret~Tilde",
                    description = "desc\\val_y#z^w~q",
                )
            val model = C4Model(id = "m", name = "M", elements = listOf(system))
            val diagram =
                SystemContextDiagram(
                    id = "ctx",
                    name = "Context",
                    elements = listOf("s"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("s")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            // All LaTeX special chars must be escaped to their safe forms.
            tex shouldContain "\\textbackslash{}"
            tex shouldContain "\\_"
            tex shouldContain "\\#"
            tex shouldContain "\\textasciicircum{}"
            tex shouldContain "\\textasciitilde{}"
            // The raw unescaped backslash-Name sequence must not appear verbatim
            // (i.e. the name must not be emitted without escaping the backslash).
            tex shouldNotContain "Sys\\Name"

            SampleOutput.write("c4/injection-name-description.tex", tex)
        }

        "Dollar sign in SoftwareSystem name is escaped to backslash-dollar" {
            // Use a name with ONLY a dollar sign so we can assert the exact escaped form.
            val dollarName = "Pay ${'$'}100"
            val system =
                C4SoftwareSystem(
                    id = "s2",
                    name = dollarName,
                    description = "Budget ${'$'}50 per unit",
                )
            val model = C4Model(id = "m", name = "M", elements = listOf(system))
            val diagram =
                SystemContextDiagram(
                    id = "ctx2",
                    name = "Context",
                    elements = listOf("s2"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("s2")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            // The $ must be escaped to \$ in the output.
            tex shouldContain "Pay \\\$100"
            tex shouldContain "Budget \\\$50"

            SampleOutput.write("c4/dollar-in-name.tex", tex)
        }

        // ─── LaTeX injection: technology field in Container ───────────────────

        "LaTeX injection chars in Container technology are escaped" {
            val container =
                C4Container(
                    id = "c",
                    name = "MyContainer",
                    technology = "Spring\\Boot\$v3_#latest^~",
                    system = "sys",
                )
            val model = C4Model(id = "m", name = "M", elements = listOf(container))
            val diagram =
                ContainerDiagram(
                    id = "ctr",
                    name = "Containers",
                    system = "sys",
                    elements = listOf("c"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("c")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "\\textbackslash{}"
            tex shouldContain "\\$"
            tex shouldContain "\\_"
            tex shouldContain "\\#"
            tex shouldContain "\\textasciicircum{}"
            tex shouldContain "\\textasciitilde{}"
            // Raw injection must not survive into the stereotype text.
            tex shouldNotContain "Spring\\Boot"

            SampleOutput.write("c4/injection-container-technology.tex", tex)
        }

        // ─── LaTeX injection: technology field in Component ───────────────────

        "LaTeX injection chars in Component technology are escaped" {
            val component =
                C4Component(
                    id = "cmp",
                    name = "OrderSvc",
                    technology = "Kotlin&JVM\$1_8#LTS",
                    container = "api",
                )
            val model = C4Model(id = "m", name = "M", elements = listOf(component))
            val diagram =
                ComponentDiagram(
                    id = "d",
                    name = "D",
                    container = "api",
                    elements = listOf("cmp"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("cmp")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "\\&"
            tex shouldContain "\\$"
            tex shouldContain "\\_"
            tex shouldContain "\\#"
            tex shouldNotContain "Kotlin&JVM"

            SampleOutput.write("c4/injection-component-technology.tex", tex)
        }

        // ─── LaTeX injection: technology field in DeploymentNode ─────────────

        "LaTeX injection chars in DeploymentNode technology are escaped" {
            val node =
                C4DeploymentNode(
                    id = "n",
                    name = "K8s",
                    technology = "AWS\\EKS\$v1_29#prod^~latest",
                )
            val model = C4Model(id = "m", name = "M", elements = listOf(node))
            val diagram =
                DeploymentDiagram(
                    id = "dep",
                    name = "Dep",
                    elements = listOf("n"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("n")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "\\textbackslash{}"
            tex shouldContain "\\$"
            tex shouldContain "\\_"
            tex shouldContain "\\#"
            tex shouldNotContain "AWS\\EKS"

            SampleOutput.write("c4/injection-deploymentnode-technology.tex", tex)
        }

        // ─── LaTeX injection: technology field in CodeElement ─────────────────

        "LaTeX injection chars in CodeElement technology are escaped" {
            val codeEl =
                C4CodeElement(
                    id = "ce",
                    name = "Foo",
                    technology = "Go\\1_21\$mod#latest",
                    component = "comp",
                )
            val model = C4Model(id = "m", name = "M", elements = listOf(codeEl))
            val diagram =
                ComponentDiagram(
                    id = "d",
                    name = "D",
                    container = "api",
                    elements = listOf("ce"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("ce")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "\\textbackslash{}"
            tex shouldContain "\\$"
            tex shouldContain "\\_"
            tex shouldContain "\\#"
            tex shouldNotContain "Go\\1_21"

            SampleOutput.write("c4/injection-codeelement-technology.tex", tex)
        }

        // ─── LaTeX injection: C4Relationship label and technology ─────────────

        "LaTeX injection chars in C4Relationship label are escaped" {
            val user = C4Person(id = "u", name = "User")
            val system = C4SoftwareSystem(id = "s", name = "System")
            val rel =
                C4Relationship(
                    id = "r",
                    source = "u",
                    target = "s",
                    label = "Uses\\API\$v2_#prod^~",
                )
            val model =
                C4Model(
                    id = "m",
                    name = "M",
                    elements = listOf(user, system),
                    relationships = listOf(rel),
                )
            val diagram =
                SystemContextDiagram(
                    id = "ctx",
                    name = "Context",
                    elements = listOf("u", "s"),
                    relationships = listOf("r"),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(400f, 200f),
                    nodes =
                        mapOf(
                            NodeId("u") to NodeLayout(bounds = Rect(Point(20f, 40f), Size(120f, 80f))),
                            NodeId("s") to NodeLayout(bounds = Rect(Point(260f, 40f), Size(120f, 80f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("r") to
                                EdgeRoute.Direct(
                                    source = Point(140f, 80f),
                                    target = Point(260f, 80f),
                                ),
                        ),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "\\textbackslash{}"
            tex shouldContain "\\$"
            tex shouldContain "\\_"
            tex shouldContain "\\#"
            tex shouldNotContain "Uses\\API"

            SampleOutput.write("c4/injection-relationship-label.tex", tex)
        }

        "LaTeX injection chars in C4Relationship technology are escaped" {
            val user = C4Person(id = "u", name = "User")
            val system = C4SoftwareSystem(id = "s", name = "System")
            val rel =
                C4Relationship(
                    id = "r",
                    source = "u",
                    target = "s",
                    label = "Calls",
                    technology = "REST\\HTTP\$v1_1#TLS^1_3~latest",
                )
            val model =
                C4Model(
                    id = "m",
                    name = "M",
                    elements = listOf(user, system),
                    relationships = listOf(rel),
                )
            val diagram =
                SystemContextDiagram(
                    id = "ctx",
                    name = "Context",
                    elements = listOf("u", "s"),
                    relationships = listOf("r"),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(400f, 200f),
                    nodes =
                        mapOf(
                            NodeId("u") to NodeLayout(bounds = Rect(Point(20f, 40f), Size(120f, 80f))),
                            NodeId("s") to NodeLayout(bounds = Rect(Point(260f, 40f), Size(120f, 80f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("r") to
                                EdgeRoute.Direct(
                                    source = Point(140f, 80f),
                                    target = Point(260f, 80f),
                                ),
                        ),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            tex shouldContain "\\textbackslash{}"
            tex shouldContain "\\$"
            tex shouldContain "\\_"
            tex shouldContain "\\#"
            tex shouldContain "\\textasciicircum{}"
            tex shouldContain "\\textasciitilde{}"
            tex shouldNotContain "REST\\HTTP"

            SampleOutput.write("c4/injection-relationship-technology.tex", tex)
        }

        // ─── Description length cap ───────────────────────────────────────────

        "Description exceeding 200 chars is truncated with an ellipsis" {
            val longDesc = "A".repeat(250)
            val system =
                C4SoftwareSystem(
                    id = "s",
                    name = "Verbose System",
                    description = longDesc,
                )
            val model = C4Model(id = "m", name = "M", elements = listOf(system))
            val diagram =
                SystemContextDiagram(
                    id = "ctx",
                    name = "Context",
                    elements = listOf("s"),
                    relationships = emptyList(),
                )
            val layout = singleNodeLayout("s")

            val tex = KumlLatexRenderer.toLatex(diagram, model, layout)

            // The full 250-char string must NOT appear verbatim.
            tex shouldNotContain longDesc
            // The truncated 200-char prefix must appear followed by the ellipsis character.
            tex shouldContain "A".repeat(200) + "…"

            SampleOutput.write("c4/description-truncation.tex", tex)
        }
    })
