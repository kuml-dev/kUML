package dev.kuml.mcp.examples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * V3.3.1 — completeness and consistency tests for [ExampleCatalog].
 *
 * The completeness test is the load-bearing one: it forces a catalog decision (curate or
 * explicitly exclude) whenever a new vault example is synced into
 * `kuml-tests/kuml-vault-examples-tests/src/test/resources/vault-examples/` and bundled by
 * `:kuml-mcp:processResources`.
 */
class ExampleCatalogTest :
    FunSpec({
        test("every bundled example md is either catalogued or explicitly excluded") {
            val catalogued = ExampleCatalog.entries.map { it.fileName }.toSet()
            val excluded = ExampleCatalog.excludedFiles.keys
            BundledExamples.listNames().forEach { name ->
                (name in catalogued || name in excluded) shouldBe true
            }
        }

        test("catalog and exclusion list do not overlap") {
            val catalogued = ExampleCatalog.entries.map { it.fileName }.toSet()
            val excluded = ExampleCatalog.excludedFiles.keys
            catalogued.intersect(excluded) shouldBe emptySet()
        }

        test("every catalogued filename resolves to a bundled resource with a non-empty kuml block") {
            ExampleCatalog.entries.forEach { example ->
                ExampleCatalog.loadScript(example).shouldNotBeBlank()
            }
        }

        test("every excluded filename actually exists among bundled resources") {
            val bundled = BundledExamples.listNames().toSet()
            ExampleCatalog.excludedFiles.keys.forEach { fileName ->
                (fileName in bundled) shouldBe true
            }
        }

        test("languages are exactly the five ExtractedDiagram families") {
            // Order follows first catalog appearance: uml (entry 01), c4 (entry 02),
            // sysml2 (entry 03), bpmn, blueprint.
            ExampleCatalog.languages() shouldBe listOf("uml", "c4", "sysml2", "bpmn", "blueprint")
        }

        test("find returns multiple examples for uml component") {
            ExampleCatalog.find(language = "uml", diagramType = "component") shouldHaveSize 2
        }

        test("find returns multiple examples for uml profile") {
            ExampleCatalog.find(language = "uml", diagramType = "profile") shouldHaveSize 3
        }

        test("find returns multiple examples for bpmn process") {
            ExampleCatalog.find(language = "bpmn", diagramType = "process") shouldHaveSize 3
        }

        test("diagramTypes(uml) contains composite-structure and interaction-overview") {
            val types = ExampleCatalog.diagramTypes("uml")
            types shouldContain "composite-structure"
            types shouldContain "interaction-overview"
        }

        test("blueprint diagramTypes distinguish journey from service-blueprint") {
            val types = ExampleCatalog.diagramTypes("blueprint")
            types shouldContain "journey"
            types shouldContain "service-blueprint"
        }

        test("unknown language yields empty diagramTypes") {
            ExampleCatalog.diagramTypes("cobol") shouldBe emptyList()
        }
    })
