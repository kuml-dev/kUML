package dev.kuml.llm.bench

import dev.kuml.llm.core.LlmMockBackend
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class BenchmarkTest :
    StringSpec({

        "BenchmarkValidator correctly validates valid kUML script" {
            val validScript =
                """
                diagram(name = "Smoke Test", type = DiagramType.CLASS) {
                    classOf("Order")
                    classOf("Customer")
                }
                """.trimIndent()
            val (valid, message) = BenchmarkValidator.validateKuml(validScript)
            valid shouldBe true
            message shouldBe "OK"
        }

        "BenchmarkValidator detects invalid kUML script" {
            val invalidScript = "this is not valid kotlin syntax!!!"
            val (valid, _) = BenchmarkValidator.validateKuml(invalidScript)
            valid shouldBe false
        }

        "BenchmarkRunner with mock backend processes all tasks" {
            val mockResponse =
                """
                diagram(name = "Mock", type = DiagramType.CLASS) {
                    classOf("MockClass")
                }
                """.trimIndent()
            val backend = LlmMockBackend(response = mockResponse)
            val report =
                BenchmarkRunner.run(
                    tasks = BENCHMARK_TASKS,
                    backend = backend,
                )
            report.totalTasks shouldBe BENCHMARK_TASKS.size
            report.backend shouldBe "mock"
            report.model shouldBe "mock"
            // All tasks should have been attempted (some may fail validation for non-KUML tools)
            report.results.size shouldBe BENCHMARK_TASKS.size
            // The mock response is valid kUML — KUML tasks should pass
            val kumlResults = report.results.filter { it.tool == BenchmarkTool.KUML }
            kumlResults.all { it.valid } shouldBe true
            // The markdown report should be generated without error
            val markdown = report.toMarkdown()
            markdown shouldContain "# kUML LLM Benchmark Report"
            markdown shouldContain "mock"
        }
    })
