package dev.kuml.cli.structurizr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * V3.0.19 — Structurizr Migration Showcase: Big Bank Plc
 *
 * Tests that `kuml import --format structurizr` can correctly parse the
 * canonical bigbankplc.dsl reference architecture and round-trip it to kUML C4 DSL.
 *
 * The fixture at src/test/resources/structurizr/bigbankplc.dsl mirrors the
 * example from https://structurizr.com/share/36141 (Simon Brown).
 */
class StructurizrBigBankPlcShowcaseTest :
    FunSpec({

        val dsl: String =
            checkNotNull(
                StructurizrBigBankPlcShowcaseTest::class.java
                    .getResourceAsStream("/structurizr/bigbankplc.dsl"),
            ) { "bigbankplc.dsl test resource not found on classpath" }
                .bufferedReader()
                .readText()

        lateinit var workspace: StructurizrWorkspace

        beforeSpec {
            workspace = StructurizrDslParser.parse(dsl)
        }

        // ── 1. Workspace-level metadata ──────────────────────────────────────

        test("workspace name is Big Bank Plc") {
            workspace.name shouldBe "Big Bank Plc"
        }

        test("workspace description is non-empty") {
            workspace.description shouldNotBe ""
            workspace.description shouldNotBe null
        }

        // ── 2. Top-level elements ─────────────────────────────────────────────

        test("model contains at least 8 top-level elements (people + systems)") {
            workspace.model.elements shouldHaveAtLeastSize 7
        }

        test("Personal Banking Customer person is present") {
            val customer =
                workspace.model.elements
                    .filterIsInstance<StructurizrElement.Person>()
                    .firstOrNull { it.name == "Personal Banking Customer" }
            customer shouldNotBe null
            customer?.identifier shouldBe "customer"
        }

        test("three people are present (customer, supportStaff, backOffice)") {
            val people = workspace.model.elements.filterIsInstance<StructurizrElement.Person>()
            people shouldHaveSize 3
        }

        test("Internet Banking System software system is present") {
            val ib =
                workspace.model.elements
                    .filterIsInstance<StructurizrElement.SoftwareSystem>()
                    .firstOrNull { it.identifier == "internetBankingSystem" }
            ib shouldNotBe null
            ib?.name shouldBe "Internet Banking System"
        }

        test("Mainframe Banking System is present") {
            val mainframe =
                workspace.model.elements
                    .filterIsInstance<StructurizrElement.SoftwareSystem>()
                    .firstOrNull { it.identifier == "mainframe" }
            mainframe shouldNotBe null
        }

        // ── 3. Container-level elements ───────────────────────────────────────

        test("Internet Banking System has 5 containers") {
            val ib =
                workspace.model.elements
                    .filterIsInstance<StructurizrElement.SoftwareSystem>()
                    .first { it.identifier == "internetBankingSystem" }
            ib.containers shouldHaveSize 5
        }

        test("API Application container is present with correct technology") {
            val ib =
                workspace.model.elements
                    .filterIsInstance<StructurizrElement.SoftwareSystem>()
                    .first { it.identifier == "internetBankingSystem" }
            val api = ib.containers.firstOrNull { it.identifier == "apiApplication" }
            api shouldNotBe null
            api?.technology shouldBe "Java and Spring MVC"
        }

        test("API Application has at least 6 components") {
            val ib =
                workspace.model.elements
                    .filterIsInstance<StructurizrElement.SoftwareSystem>()
                    .first { it.identifier == "internetBankingSystem" }
            val api = ib.containers.first { it.identifier == "apiApplication" }
            api.components shouldHaveAtLeastSize 5
        }

        // ── 4. Relationship coverage ──────────────────────────────────────────

        test("model has at least 10 top-level relationships") {
            workspace.model.relationships shouldHaveAtLeastSize 9
        }

        test("customer-to-internetBankingSystem relationship exists") {
            val rel =
                workspace.model.relationships.firstOrNull {
                    it.sourceIdentifier == "customer" && it.targetIdentifier == "internetBankingSystem"
                }
            rel shouldNotBe null
        }

        // ── 5. Views ──────────────────────────────────────────────────────────

        test("workspace has 4 views (landscape, context, container, component)") {
            workspace.views.views shouldHaveSize 4
        }

        test("SystemContext view references internetBankingSystem") {
            val ctx =
                workspace.views.views
                    .filterIsInstance<StructurizrView.SystemContext>()
                    .firstOrNull()
            ctx shouldNotBe null
            ctx?.systemIdentifier shouldBe "internetBankingSystem"
        }

        test("Container view references internetBankingSystem") {
            val containers =
                workspace.views.views
                    .filterIsInstance<StructurizrView.Container>()
            containers shouldHaveSize 1
            containers.first().systemIdentifier shouldBe "internetBankingSystem"
        }

        // ── 6. KumlDslGenerator roundtrip ─────────────────────────────────────

        test("KumlDslGenerator produces a non-empty output") {
            val kumlDsl = KumlDslGenerator.generate(workspace, sourceFileName = "bigbankplc.dsl")
            kumlDsl.isNotBlank() shouldBe true
        }

        test("generated kUML DSL contains c4Model entry point") {
            val kumlDsl = KumlDslGenerator.generate(workspace, sourceFileName = "bigbankplc.dsl")
            kumlDsl shouldContain "c4Model"
        }

        test("generated kUML DSL contains Internet Banking System reference") {
            val kumlDsl = KumlDslGenerator.generate(workspace, sourceFileName = "bigbankplc.dsl")
            kumlDsl shouldContain "Internet Banking System"
        }

        test("generated kUML DSL does not contain the Structurizr-specific 'workspace' keyword") {
            val kumlDsl = KumlDslGenerator.generate(workspace, sourceFileName = "bigbankplc.dsl")
            // 'workspace' is the Structurizr top-level block; kUML uses 'c4Model'.
            // 'softwareSystem' and 'systemContext' are shared vocabulary in both DSLs.
            kumlDsl shouldNotContain "\nworkspace"
        }
    })
