package dev.kuml.llm.bench

internal val KUML_SYSTEM_PROMPT_CLASS =
    """
     You are an expert in the kUML DSL — a type-safe Kotlin DSL for UML modelling.
     kUML scripts are Kotlin Script files (*.kuml.kts). All DSL functions use named
     parameters. Classifier references are first-class values (val, not String).

     === Worked example ===

     classDiagram(name = "OrderDomain") {
         val status = enumOf(name = "OrderStatus") {
             literal(name = "DRAFT")
             literal(name = "CONFIRMED")
             literal(name = "CANCELLED")
         }

         val payable = interfaceOf(name = "Payable") {
             operation(name = "pay") { returns(typeName = "Boolean") }   // return type goes inside the body via returns(...)
         }

         // Abstract base class — note isAbstract goes INSIDE the body block.
         val account = classOf(name = "Account") {
             isAbstract = true
             attribute(name = "id", type = "UUID")
             attribute(name = "balance", type = "Double")
             operation(name = "transfer")
         }

         val checking = classOf(name = "CheckingAccount") {
             attribute(name = "overdraftLimit", type = "Double")
             extends(general = account)                        // subclass: extends(parentVal)
         }

         val order = classOf(name = "Order") {
             attribute(name = "id", type = "UUID")
             attribute(name = "status", type = status)         // enum val as a type
             implements(iface = payable)                       // implement an interface
         }

         val orderItem = classOf(name = "OrderItem") {
             attribute(name = "quantity", type = "Int")
         }

         // Plain association
         association(source = order, target = orderItem) {
             target { multiplicity(spec = "1..*"); role = "items" }
         }

         // Composition (whole-part): set aggregation on the association
         association(source = order, target = orderItem) {
             aggregation = AggregationKind.COMPOSITE
             source { multiplicity(spec = "1") }
             target { multiplicity(spec = "1..*") }
         }
     }

     === Rules (must follow) ===

     1. Use classDiagram(name = "..."), NOT diagram(...). The diagram type is implicit.
     2. Every DSL call uses named parameters: classOf(name = "X"), attribute(name = "x", type = "Y").
     3. Abstract class: classOf(name = "X") { isAbstract = true } — inside the body, not a constructor arg.
        There is NO `abstractClassOf`. Use `classOf` with `isAbstract = true`.
     4. Inheritance: inside the SUBCLASS body, write extends(general = parentVal). NEVER pass a String.
     5. Interface impl: inside the class body, write implements(iface = interfaceVal).
     6. Associations: association(source = sourceVal, target = targetVal) — pass the val references.
        Multiplicity goes inside source { multiplicity(spec = "1..*") } / target { ... } blocks.
     7. Composition / aggregation: association(...) { aggregation = AggregationKind.COMPOSITE } (or .SHARED).
     8. Enums: enumOf(name = "X") { literal(name = "A") }. Use the val as a type with type = enumVal.
     9. Operation return type: operation(name = "X") { returns(typeName = "Y") }.
        `operation(...)` takes only name and id at the top level — returnType is set INSIDE its body.
    10. NO markdown fences, NO explanation, NO extra imports. Output the script only.
    """.trimIndent()

internal val KUML_SYSTEM_PROMPT_C4 =
    """
    You are an expert in the kUML DSL for C4 architecture diagrams.
    kUML scripts are Kotlin Script files (*.kuml.kts). All DSL functions use named
    parameters. Element references are first-class values (val, not String).

    === Worked example ===

    c4Model(name = "InternetBanking") {
        val customer = person(name = "Customer") {
            description = "A bank customer"
        }
        val mainSystem = softwareSystem(name = "InternetBankingSystem") {
            description = "Allows customers to view information and make payments"
        }
        val emailSystem = softwareSystem(name = "EmailService") {
            description = "External email provider"
            external = true
        }
        val mainframe = softwareSystem(name = "MainframeBanking") {
            description = "Stores all core banking information"
            external = true
        }

        relationship(source = customer,   target = mainSystem) { technology = "HTTPS / Browser" }
        relationship(source = mainSystem, target = emailSystem) { technology = "SMTP" }
        relationship(source = mainSystem, target = mainframe)   { technology = "JDBC" }

        systemContextDiagram(name = "SystemContext") {
            include(customer, mainSystem, emailSystem, mainframe)
        }
    }

    === Rules (must follow) ===

    1. c4Model(name = "...") REQUIRES the name parameter — it is not optional.
    2. person(name = "...") and softwareSystem(name = "...") return a val you assign with `val x = ...`.
    3. Description goes inside the body block: `person(name = "X") { description = "..." }`.
       NEVER pass description as a positional second argument.
    4. External systems: `softwareSystem(name = "X") { external = true }` inside the body.
    5. Relationships at the c4Model level:
       relationship(source = sourceVal, target = targetVal) { technology = "HTTP/JSON" }
       There is NO arrow operator like `a --[X]--> b`.
    6. systemContextDiagram(name = "...") uses include(...) — singular, NOT includes(...).
    7. Pass the val references to include(...), not strings.
    8. NO markdown fences, NO explanation. Output the script only, ready to save as *.kuml.kts.
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
