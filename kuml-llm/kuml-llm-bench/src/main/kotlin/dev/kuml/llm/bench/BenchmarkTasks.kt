package dev.kuml.llm.bench

internal val KUML_SYSTEM_PROMPT_CLASS =
    """
    You are an expert in the kUML DSL (Kotlin-based UML modelling language).
    kUML scripts are Kotlin Script files (*.kuml.kts). Here is the syntax for class diagrams:

    diagram(name = "Example", type = DiagramType.CLASS) {
        classOf("ClassName") {
            attribute("attrName", "TypeName")
            operation("methodName")
        }
        interfaceOf("IName") {
            operation("doSomething")
        }
        enumOf("Status") {
            literal("ACTIVE")
            literal("INACTIVE")
        }
        association("SourceClass", "TargetClass")
        generalization(specific = "SubClass", general = "SuperClass")
    }

    Respond with ONLY the kUML script. No explanation, no markdown fences.
    """.trimIndent()

internal val KUML_SYSTEM_PROMPT_C4 =
    """
    You are an expert in the kUML DSL for C4 diagrams.
    kUML C4 system context diagrams look like:

    c4Model {
        val user = person("User", "A human user")
        val system = softwareSystem("SystemName", "Description")
        val external = softwareSystem("ExternalSystem", "Description", external = true)
        systemContextDiagram("Context") {
            includes(user, system, external)
            user --[Uses]--> system
            system --[Calls]--> external
        }
    }

    Respond with ONLY the kUML script. No explanation, no markdown fences.
    """.trimIndent()

internal val PLANTUML_SYSTEM_PROMPT_CLASS =
    """
    You are an expert in PlantUML. Respond ONLY with valid PlantUML class diagram code.
    Start with @startuml and end with @enduml. No explanation.
    """.trimIndent()

internal val MERMAID_SYSTEM_PROMPT_CLASS =
    """
    You are an expert in Mermaid diagrams. Respond ONLY with a valid Mermaid class diagram.
    Start with 'classDiagram'. No explanation, no markdown fences.
    """.trimIndent()

/** The official V1 benchmark task suite. */
public val BENCHMARK_TASKS: List<BenchmarkTask> =
    listOf(
        // ── kUML class diagram tasks ──────────────────────────────────────────
        BenchmarkTask(
            id = "kuml-class-de-001",
            tool = BenchmarkTool.KUML,
            language = BenchmarkLanguage.DE,
            diagramType = DiagramType.CLASS,
            systemPrompt = KUML_SYSTEM_PROMPT_CLASS,
            userPrompt =
                """
                Erstelle ein kUML-Klassendiagramm mit dem Namen "Bestellsystem".
                Es soll folgende Klassen enthalten:
                - Bestellung (Attribute: id: UUID, betrag: Double, status: String; Operationen: bestätigen, stornieren)
                - Kunde (Attribute: name: String, email: String)
                - Assoziation zwischen Bestellung und Kunde
                """.trimIndent(),
            expectedElements = listOf("Bestellung", "Kunde"),
        ),
        BenchmarkTask(
            id = "kuml-class-en-001",
            tool = BenchmarkTool.KUML,
            language = BenchmarkLanguage.EN,
            diagramType = DiagramType.CLASS,
            systemPrompt = KUML_SYSTEM_PROMPT_CLASS,
            userPrompt =
                """
                Create a kUML class diagram named "OrderSystem".
                Include the following:
                - Order class with attributes: id (UUID), amount (Double), status (String); operations: confirm(), cancel()
                - Customer class with attributes: name (String), email (String)
                - An association between Order and Customer
                """.trimIndent(),
            expectedElements = listOf("Order", "Customer"),
        ),
        BenchmarkTask(
            id = "kuml-class-de-002",
            tool = BenchmarkTool.KUML,
            language = BenchmarkLanguage.DE,
            diagramType = DiagramType.CLASS,
            systemPrompt = KUML_SYSTEM_PROMPT_CLASS,
            userPrompt =
                """
                Erstelle ein kUML-Klassendiagramm "Fahrzeugverwaltung" mit:
                - Abstraktes Fahrzeug (Attribute: kennzeichen: String, baujahr: Int)
                - PKW erweitert Fahrzeug (Attribute: türenanzahl: Int)
                - LKW erweitert Fahrzeug (Attribute: nutzlast: Double)
                - Enum FahrzeugStatus mit AKTIV, DEFEKT, AUSSER_BETRIEB
                """.trimIndent(),
            expectedElements = listOf("Fahrzeug", "PKW", "LKW"),
        ),
        BenchmarkTask(
            id = "kuml-class-en-002",
            tool = BenchmarkTool.KUML,
            language = BenchmarkLanguage.EN,
            diagramType = DiagramType.CLASS,
            systemPrompt = KUML_SYSTEM_PROMPT_CLASS,
            userPrompt =
                """
                Create a kUML class diagram "BankingSystem":
                - Abstract Account with attributes: iban (String), balance (Double); operation: transfer(amount: Double)
                - CheckingAccount extends Account (attribute: overdraftLimit: Double)
                - SavingsAccount extends Account (attribute: interestRate: Double)
                - Customer class with name (String); composition to Account (a customer owns multiple accounts)
                """.trimIndent(),
            expectedElements = listOf("Account", "CheckingAccount", "SavingsAccount", "Customer"),
        ),
        // ── kUML C4 tasks ────────────────────────────────────────────────────
        BenchmarkTask(
            id = "kuml-c4-de-001",
            tool = BenchmarkTool.KUML,
            language = BenchmarkLanguage.DE,
            diagramType = DiagramType.C4_SYSTEM_CONTEXT,
            systemPrompt = KUML_SYSTEM_PROMPT_C4,
            userPrompt =
                """
                Erstelle ein kUML C4-System-Context-Diagramm für einen Online-Shop:
                - Benutzer (Person): Käufer, der Produkte bestellt
                - OnlineShop (SoftwareSystem): Hauptsystem
                - Zahlungsanbieter (externes SoftwareSystem): verarbeitet Zahlungen
                - Versanddienstleister (externes SoftwareSystem): liefert Pakete
                - Benutzer benutzt den OnlineShop
                - OnlineShop ruft Zahlungsanbieter auf
                - OnlineShop ruft Versanddienstleister auf
                """.trimIndent(),
            expectedElements = listOf("OnlineShop"),
        ),
        BenchmarkTask(
            id = "kuml-c4-en-001",
            tool = BenchmarkTool.KUML,
            language = BenchmarkLanguage.EN,
            diagramType = DiagramType.C4_SYSTEM_CONTEXT,
            systemPrompt = KUML_SYSTEM_PROMPT_C4,
            userPrompt =
                """
                Create a kUML C4 system context diagram for a banking application:
                - Customer (Person): end user managing accounts
                - BankingApp (SoftwareSystem): the main mobile/web application
                - CoreBanking (external SoftwareSystem): processes transactions
                - NotificationService (external SoftwareSystem): sends emails/SMS
                - Customer uses BankingApp
                - BankingApp calls CoreBanking for transactions
                - BankingApp sends notifications via NotificationService
                """.trimIndent(),
            expectedElements = listOf("BankingApp"),
        ),
        // ── PlantUML comparison tasks ─────────────────────────────────────────
        BenchmarkTask(
            id = "plantuml-class-de-001",
            tool = BenchmarkTool.PLANTUML,
            language = BenchmarkLanguage.DE,
            diagramType = DiagramType.CLASS,
            systemPrompt = PLANTUML_SYSTEM_PROMPT_CLASS,
            userPrompt =
                """
                Erstelle ein PlantUML-Klassendiagramm mit den Klassen Order (id: UUID, amount: Double)
                und Customer (name: String, email: String) sowie einer Assoziation zwischen ihnen.
                """.trimIndent(),
            expectedElements = listOf("Order", "Customer"),
        ),
        BenchmarkTask(
            id = "plantuml-class-en-001",
            tool = BenchmarkTool.PLANTUML,
            language = BenchmarkLanguage.EN,
            diagramType = DiagramType.CLASS,
            systemPrompt = PLANTUML_SYSTEM_PROMPT_CLASS,
            userPrompt =
                """
                Create a PlantUML class diagram showing: abstract class Vehicle (licensePlate: String, year: Int),
                Car extends Vehicle (doors: Int), Truck extends Vehicle (payload: Double).
                """.trimIndent(),
            expectedElements = listOf("Vehicle", "Car", "Truck"),
        ),
        // ── Mermaid comparison tasks ──────────────────────────────────────────
        BenchmarkTask(
            id = "mermaid-class-de-001",
            tool = BenchmarkTool.MERMAID,
            language = BenchmarkLanguage.DE,
            diagramType = DiagramType.CLASS,
            systemPrompt = MERMAID_SYSTEM_PROMPT_CLASS,
            userPrompt =
                """
                Erstelle ein Mermaid-Klassendiagramm mit Order (id, amount, status)
                und Customer (name, email) mit Assoziation.
                """.trimIndent(),
            expectedElements = listOf("Order", "Customer"),
        ),
        BenchmarkTask(
            id = "mermaid-class-en-001",
            tool = BenchmarkTool.MERMAID,
            language = BenchmarkLanguage.EN,
            diagramType = DiagramType.CLASS,
            systemPrompt = MERMAID_SYSTEM_PROMPT_CLASS,
            userPrompt =
                """
                Create a Mermaid class diagram showing Account (abstract, iban, balance),
                CheckingAccount extends Account, SavingsAccount extends Account.
                """.trimIndent(),
            expectedElements = listOf("Account"),
        ),
    )
