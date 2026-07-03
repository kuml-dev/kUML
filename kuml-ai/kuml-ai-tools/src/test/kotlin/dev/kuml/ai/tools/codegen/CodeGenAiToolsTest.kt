package dev.kuml.ai.tools.codegen

import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.PatchApplyResult
import dev.kuml.codegen.api.CodeGenRegistry
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import io.kotest.matchers.string.shouldContain as shouldContainString

/**
 * Unit tests for [CodeGenAiTools].
 *
 * V3.1.20.
 */
class CodeGenAiToolsTest :
    FunSpec({

        fun makeTools(ctx: AgentEditingContext = AgentEditingContext.emptyUml()): Pair<AgentEditingContext, CodeGenAiTools> =
            ctx to CodeGenAiTools(ctx)

        // ── add_jpa_entity ────────────────────────────────────────────────────

        test("add_jpa_entity produces a class with 'entity' stereotype and given attributes") {
            val (ctx, tools) = makeTools()
            runTest {
                val result =
                    tools.addJpaEntity(
                        className = "Product",
                        tableName = "products",
                        fields =
                            listOf(
                                CodeGenAiTools.FieldSpec("id", "Long"),
                                CodeGenAiTools.FieldSpec("name", "String"),
                            ),
                    )
                result.shouldBeInstanceOf<PatchApplyResult.Success>()

                val model = ctx.resolveModel() as AnyKumlModel.Uml
                val cls = model.elements.filterIsInstance<UmlClass>().firstOrNull { it.name == "Product" }
                cls shouldNotBe null
                cls!!.stereotypes shouldContain "entity"
                cls.attributes.map { it.name } shouldBe listOf("id", "name")
            }
        }

        test("add_jpa_entity with no fields creates a class with 'entity' stereotype only") {
            val (ctx, tools) = makeTools()
            runTest {
                val result = tools.addJpaEntity("User", "users")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()

                val model = ctx.resolveModel() as AnyKumlModel.Uml
                val cls = model.elements.filterIsInstance<UmlClass>().first { it.name == "User" }
                cls.stereotypes shouldContain "entity"
                cls.attributes shouldHaveSize 0
            }
        }

        test("add_jpa_entity records AddElement patch with jpa.table payload") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addJpaEntity("Order", "orders")
                val patches = ctx.patches()
                patches shouldHaveSize 1
                val patch = patches.first() as dev.kuml.ai.tools.context.ModelPatch.AddElement
                patch.payload["jpa.table"] shouldBe "orders"
                patch.payload["stereotype"] shouldBe "entity"
            }
        }

        // ── add_spring_bean ───────────────────────────────────────────────────

        test("add_spring_bean adds the bean class with correct stereotype") {
            val (ctx, tools) = makeTools()
            runTest {
                val result = tools.addSpringBean("OrderService", "service")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()

                val model = ctx.resolveModel() as AnyKumlModel.Uml
                val cls = model.elements.filterIsInstance<UmlClass>().first { it.name == "OrderService" }
                cls.stereotypes shouldContain "service"
            }
        }

        test("add_spring_bean with dependencies adds one relationship per dependency") {
            val (ctx, tools) = makeTools()
            runTest {
                // Add dependency targets first
                tools.addJpaEntity("Order", "orders")
                tools.addJpaEntity("Customer", "customers")

                val result = tools.addSpringBean("OrderService", "service", listOf("Order", "Customer"))
                result.shouldBeInstanceOf<PatchApplyResult.Success>()

                val model = ctx.resolveModel() as AnyKumlModel.Uml
                // 2 entities + 1 service = 3 elements
                model.elements.filterIsInstance<UmlClass>() shouldHaveSize 3
                // 2 associations
                model.relationships shouldHaveSize 2
            }
        }

        test("add_spring_bean with unknown dependency returns Failure with hint") {
            val (ctx, tools) = makeTools()
            runTest {
                val result = tools.addSpringBean("PaymentService", "service", listOf("NonExistent"))
                val failure = result.shouldBeInstanceOf<PatchApplyResult.Failure>()
                failure.hint shouldNotBe null
            }
        }

        test("add_spring_bean with invalid beanType returns Failure") {
            val (ctx, tools) = makeTools()
            runTest {
                val result = tools.addSpringBean("Foo", "singleton")
                result.shouldBeInstanceOf<PatchApplyResult.Failure>()
            }
        }

        // ── generate_code ─────────────────────────────────────────────────────

        test("generate_code with UML model and kotlin generator writes files to temp dir") {
            val (ctx, tools) = makeTools()
            val tempDir = Files.createTempDirectory("kuml-codegen-ai-test")
            try {
                runTest {
                    // Seed model with a class
                    tools.addJpaEntity("Product", "products", listOf(CodeGenAiTools.FieldSpec("id", "Long")))

                    // Ensure CodeGenRegistry has the kotlin generator
                    if (CodeGenRegistry.names().isEmpty()) {
                        CodeGenRegistry.loadFromClasspath()
                    }

                    if ("kotlin" in CodeGenRegistry.names()) {
                        val result = tools.generateCode("kotlin", tempDir.toString())
                        val success = result.shouldBeInstanceOf<CodeGenAiTools.CodeGenResult.Success>()
                        success.generatedFiles.shouldNotBeEmpty()
                        success.generatorId shouldBe "kotlin"
                    } else {
                        // No kotlin generator on classpath in this test context — test generates with unknown plugin
                        val result = tools.generateCode("kotlin", tempDir.toString())
                        result.shouldBeInstanceOf<CodeGenAiTools.CodeGenResult.Failure>()
                    }
                }
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        test("generate_code with C4 model returns Failure") {
            val ctx = AgentEditingContext.emptyC4()
            val tools = CodeGenAiTools(ctx)
            val tempDir = Files.createTempDirectory("kuml-codegen-c4-test")
            try {
                runTest {
                    val result = tools.generateCode("kotlin", tempDir.toString())
                    val failure = result.shouldBeInstanceOf<CodeGenAiTools.CodeGenResult.Failure>()
                    failure.reason shouldContainString "C4"
                }
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        test("generate_code with SysML 2 model returns Failure") {
            val ctx = AgentEditingContext.emptySysml2()
            val tools = CodeGenAiTools(ctx)
            val tempDir = Files.createTempDirectory("kuml-codegen-sysml2-test")
            try {
                runTest {
                    val result = tools.generateCode("kotlin", tempDir.toString())
                    val failure = result.shouldBeInstanceOf<CodeGenAiTools.CodeGenResult.Failure>()
                    failure.reason shouldContainString "SysML 2"
                }
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        test("generate_code with path traversal rejected") {
            val (_, tools) = makeTools()
            runTest {
                val result = tools.generateCode("kotlin", "../../etc/passwd")
                val failure = result.shouldBeInstanceOf<CodeGenAiTools.CodeGenResult.Failure>()
                failure.reason shouldContainString "path traversal"
            }
        }

        test("generate_code with unknown generator returns Failure with registry info") {
            val (ctx, tools) = makeTools()
            val tempDir = Files.createTempDirectory("kuml-codegen-unknown-test")
            try {
                runTest {
                    tools.addJpaEntity("Foo", "foos")
                    val result = tools.generateCode("nonexistent-language", tempDir.toString())
                    result.shouldBeInstanceOf<CodeGenAiTools.CodeGenResult.Failure>()
                }
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    })
