package dev.kuml.mcp.examples

/**
 * One curated bundled example, keyed by its exact bundled filename.
 *
 * @property fileName exact bundled name, including the EN DASH (U+2013) and umlauts used
 *   by the vault notes, e.g. "01 UML Klasse – Order Domain.md". Must match a name
 *   returned by [BundledExamples.listNames] byte-for-byte.
 * @property language one of [ExampleCatalog.languages] — `uml`, `sysml2`, `c4`, `bpmn`,
 *   or `blueprint`.
 * @property diagramType kebab-case diagram-type token within [language], e.g. `class`,
 *   `sequence`, `composite-structure`, `bdd`, `container`, `service-blueprint`, `journey`.
 * @property description one-sentence English description of what the example shows.
 */
internal data class CuratedExample(
    val fileName: String,
    val language: String,
    val diagramType: String,
    val description: String,
) {
    /** Source note title — the filename without the trailing ".md". */
    val sourceNote: String get() = fileName.removeSuffix(".md")
}

/**
 * Hand-maintained mapping from bundled `*.md` files under `dsl/examples` to (language,
 * diagramType, description) — the single source of truth backing both the `kuml.examples`
 * MCP tool and the granular `kuml://dsl/examples/<language>/<diagramType>` resources.
 *
 * The language taxonomy mirrors the five [dev.kuml.core.script.ExtractedDiagram] families,
 * in catalog order: `uml`, `c4`, `sysml2`, `bpmn`, `blueprint`. `journey` is a **diagramType** within
 * `blueprint` (both `journeyDiagram(...)` and `blueprintDiagram(...)` build a
 * `BlueprintModel`) — not a separate language.
 *
 * Classification is by DSL entry-point builder, not by the note's German title: e.g.
 * "10 BPMN zu UML-Aktivität" opens with `bpmnModel("...")` and is classified `bpmn/process`,
 * even though it also contains a second (non-curated) UML-activity `kuml` block.
 */
internal object ExampleCatalog {
    /** Bundled files intentionally not served, keyed by filename, with a machine-readable reason. */
    internal val excludedFiles: Map<String, String> =
        mapOf(
            "00 Übersicht.md" to "index-note-no-dsl-block",
            "07 BPMN animiert – PdV Mitgliedsantrag.md" to "smil-animation-variant",
            "08 STM animiert – Traffic Light.md" to "smil-animation-variant",
            "19 UML Sequence animiert – API Submit.md" to "smil-animation-variant",
        )

    internal val entries: List<CuratedExample> =
        listOf(
            CuratedExample(
                fileName = "01 UML Klasse – Order Domain.md",
                language = "uml",
                diagramType = "class",
                description =
                    "UML class diagram of an order domain — classes, interface, enum, association, " +
                        "composition, dependency, generalization, OCL constraint.",
            ),
            CuratedExample(
                fileName = "02 C4 Container – Internet Banking.md",
                language = "c4",
                diagramType = "container",
                description = "Minimal C4 container diagram for an internet-banking system.",
            ),
            CuratedExample(
                fileName = "03 SysML 2 BDD – Hybrid Vehicle.md",
                language = "sysml2",
                diagramType = "bdd",
                description = "SysML 2 block-definition diagram of a hybrid vehicle's part structure.",
            ),
            CuratedExample(
                fileName = "04 SysML 2 STM – Traffic Light.md",
                language = "sysml2",
                diagramType = "stm",
                description = "SysML 2 state-machine diagram of a traffic-light controller.",
            ),
            CuratedExample(
                fileName = "05 SysML 2 UC – Library System.md",
                language = "sysml2",
                diagramType = "uc",
                description = "SysML 2 use-case diagram for a library system.",
            ),
            CuratedExample(
                fileName = "06 SysML 2 SEQ – Login Flow.md",
                language = "sysml2",
                diagramType = "seq",
                description = "SysML 2 sequence diagram of a login flow with a combined fragment.",
            ),
            CuratedExample(
                fileName = "07 SysML 2 ACT – Order Processing.md",
                language = "sysml2",
                diagramType = "act",
                description = "SysML 2 activity diagram of order processing with action pins.",
            ),
            CuratedExample(
                fileName = "08 SysML 2 REQ – Vehicle Requirements.md",
                language = "sysml2",
                diagramType = "req",
                description = "SysML 2 requirement diagram for vehicle requirements.",
            ),
            CuratedExample(
                fileName = "09 C4 Landscape – Enterprise Banking.md",
                language = "c4",
                diagramType = "landscape",
                description = "C4 system-landscape diagram for enterprise banking.",
            ),
            CuratedExample(
                fileName = "10 BPMN zu UML-Aktivität – PdV Prozess.md",
                language = "bpmn",
                diagramType = "process",
                description =
                    "BPMN process model (PdV membership) authored via `bpmnModel { }`, illustrating " +
                        "the BPMN-to-UML-activity transformation bridge.",
            ),
            CuratedExample(
                fileName = "10 UML Objekt – Order Snapshot.md",
                language = "uml",
                diagramType = "object",
                description = "UML object diagram — a concrete instance snapshot of the order domain.",
            ),
            CuratedExample(
                fileName = "11 UML Paket – Domain Modules.md",
                language = "uml",
                diagramType = "package",
                description = "UML package diagram of domain modules.",
            ),
            CuratedExample(
                fileName = "12 UML Component – Order Architecture.md",
                language = "uml",
                diagramType = "component",
                description = "UML component diagram of an order-service architecture.",
            ),
            CuratedExample(
                fileName = "13 UML Composite Structure – Order Internals.md",
                language = "uml",
                diagramType = "composite-structure",
                description = "UML composite-structure diagram of order-service internals.",
            ),
            CuratedExample(
                fileName = "14 UML Deployment – Cloud Stack.md",
                language = "uml",
                diagramType = "deployment",
                description = "UML deployment diagram of a production cloud stack.",
            ),
            CuratedExample(
                fileName = "15 UML Profil – Java EE Profile.md",
                language = "uml",
                diagramType = "profile",
                description = "UML profile diagram defining a Java EE stereotype profile.",
            ),
            CuratedExample(
                fileName = "16 UML Use Case – Online Shop.md",
                language = "uml",
                diagramType = "use-case",
                description = "UML use-case diagram for an online shop.",
            ),
            CuratedExample(
                fileName = "17 UML Activity – Checkout Flow.md",
                language = "uml",
                diagramType = "activity",
                description = "UML activity diagram of a checkout flow.",
            ),
            CuratedExample(
                fileName = "18 UML State Machine – Order Lifecycle.md",
                language = "uml",
                diagramType = "state-machine",
                description = "UML state-machine diagram of an order lifecycle.",
            ),
            CuratedExample(
                fileName = "19 UML Sequence – API Submit.md",
                language = "uml",
                diagramType = "sequence",
                description = "UML sequence diagram of an order-placement API submit.",
            ),
            CuratedExample(
                fileName = "20 UML Communication – Place Order.md",
                language = "uml",
                diagramType = "communication",
                description = "UML communication diagram of placing an order.",
            ),
            CuratedExample(
                fileName = "21 UML Timing – TCP Handshake.md",
                language = "uml",
                diagramType = "timing",
                description = "UML timing diagram of a TCP three-way handshake.",
            ),
            CuratedExample(
                fileName = "22 UML Interaction Overview – Order Process.md",
                language = "uml",
                diagramType = "interaction-overview",
                description = "UML interaction-overview diagram of an order process.",
            ),
            CuratedExample(
                fileName = "23 C4 Context – Internet Banking.md",
                language = "c4",
                diagramType = "context",
                description = "C4 system-context diagram for internet banking.",
            ),
            CuratedExample(
                fileName = "24 C4 Component – Web App Internals.md",
                language = "c4",
                diagramType = "component",
                description = "C4 component diagram of a web-app container's internals.",
            ),
            CuratedExample(
                fileName = "25 C4 Deployment – AWS Production.md",
                language = "c4",
                diagramType = "deployment",
                description = "C4 deployment diagram of an AWS production environment.",
            ),
            CuratedExample(
                fileName = "26 C4 Dynamic – Checkout Flow.md",
                language = "c4",
                diagramType = "dynamic",
                description = "C4 dynamic diagram of a checkout flow.",
            ),
            CuratedExample(
                fileName = "27 SysML 2 IBD – Hybrid Vehicle Wiring.md",
                language = "sysml2",
                diagramType = "ibd",
                description = "SysML 2 internal-block diagram of hybrid-vehicle wiring.",
            ),
            CuratedExample(
                fileName = "28 SysML 2 PAR – Newton.md",
                language = "sysml2",
                diagramType = "par",
                description = "SysML 2 parametric diagram binding Newton's-law constraints.",
            ),
            CuratedExample(
                fileName = "29 UML Profil – AUTOSAR.md",
                language = "uml",
                diagramType = "profile",
                description = "UML profile diagram defining an AUTOSAR Adaptive Platform profile.",
            ),
            CuratedExample(
                fileName = "30 BPMN Process – Order Fulfillment.md",
                language = "bpmn",
                diagramType = "process",
                description = "BPMN process model of order fulfillment.",
            ),
            CuratedExample(
                fileName = "31 BPMN Process – Sub-Process Loop.md",
                language = "bpmn",
                diagramType = "process",
                description = "BPMN process model with a looping sub-process.",
            ),
            CuratedExample(
                fileName = "32 BPMN Collaboration – Customer und Supplier.md",
                language = "bpmn",
                diagramType = "collaboration",
                description = "BPMN collaboration diagram between customer and supplier pools.",
            ),
            CuratedExample(
                fileName = "33 Blueprint – PdV Mitglieder-Journey.md",
                language = "blueprint",
                diagramType = "service-blueprint",
                description =
                    "Full service blueprint (four layers, three lines, emotion curve) of the " +
                        "PdV member journey.",
            ),
            CuratedExample(
                fileName = "34 User Journey – PdV Mitglieder-Journey.md",
                language = "blueprint",
                diagramType = "journey",
                description = "User-journey map (emotion curve) of the PdV member journey.",
            ),
            CuratedExample(
                fileName = "35 AUTOSAR Classic – SW-Komponenten.md",
                language = "uml",
                diagramType = "component",
                description =
                    "UML component diagram of AUTOSAR Classic software components with " +
                        "provided/required ports.",
            ),
            CuratedExample(
                fileName = "36 BPMN Conversation – PdV Kommunikation.md",
                language = "bpmn",
                diagramType = "conversation",
                description = "BPMN conversation diagram of PdV communication.",
            ),
            CuratedExample(
                fileName = "37 BPMN Choreography – Bestellprozess.md",
                language = "bpmn",
                diagramType = "choreography",
                description = "BPMN choreography diagram of an ordering process.",
            ),
        )

    /** Distinct languages in catalog order: uml, c4, sysml2, bpmn, blueprint. */
    internal fun languages(): List<String> = entries.map { it.language }.distinct()

    /** Distinct diagramTypes for [language], in catalog order; empty if [language] is unknown. */
    internal fun diagramTypes(language: String): List<String> =
        entries
            .filter { it.language == language }
            .map { it.diagramType }
            .distinct()

    /** All curated examples for a (language, diagramType) pair; may contain more than one entry. */
    internal fun find(
        language: String,
        diagramType: String,
    ): List<CuratedExample> = entries.filter { it.language == language && it.diagramType == diagramType }

    /** All curated examples for [language]. */
    internal fun byLanguage(language: String): List<CuratedExample> = entries.filter { it.language == language }

    /**
     * Loads and extracts the first ` ```kuml ` fenced block for [example].
     * @throws IllegalStateException if the bundled file is missing or has no fenced block.
     */
    internal fun loadScript(example: CuratedExample): String {
        val markdown =
            BundledExamples.readRaw(example.fileName)
                ?: error("Missing bundled example resource: '${example.fileName}'")
        return BundledExamples.extractKumlBlock(markdown)
            ?: error("Bundled example '${example.fileName}' has no ```kuml fenced block")
    }
}
