package dev.kuml.codegen.m2m

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class TransformerRegistryTest :
    FunSpec(body = {

        beforeEach { TransformerRegistry.clear() }
        afterEach { TransformerRegistry.clear() }

        test("register and get by id returns correct transformer") {
            val stub =
                object : KumlTransformer<String, String> {
                    override val id = "stub"
                    override val description = "Stub transformer"

                    override fun transform(
                        source: String,
                        ctx: TransformContext,
                    ) = TransformResult.Success(source, TransformTrace())
                }
            TransformerRegistry.register(stub)

            val result = TransformerRegistry.get<String, String>("stub")
            result.shouldNotBeNull()
            result.id shouldBe "stub"
        }

        test("ids() returns all registered ids sorted alphabetically") {
            val ids = listOf("zebra-transformer", "alpha-transformer", "middle-transformer")
            ids.forEach { id ->
                TransformerRegistry.register(
                    object : KumlTransformer<Unit, Unit> {
                        override val id = id
                        override val description = "desc"

                        override fun transform(
                            source: Unit,
                            ctx: TransformContext,
                        ) = TransformResult.Success(Unit, TransformTrace())
                    },
                )
            }
            TransformerRegistry.ids() shouldBe
                listOf(
                    "alpha-transformer",
                    "middle-transformer",
                    "zebra-transformer",
                )
        }

        test("loadFromClasspath discovers UmlToJpaTransformerProvider via ServiceLoader") {
            TransformerRegistry.loadFromClasspath()
            TransformerRegistry.ids() shouldContain "uml-to-jpa"
        }

        test("get with unknown id returns null") {
            val result = TransformerRegistry.get<String, String>("no-such-transformer")
            result.shouldBeNull()
        }

        test("descriptions returns map of id to description") {
            val stub =
                object : KumlTransformer<Unit, Unit> {
                    override val id = "my-transformer"
                    override val description = "Does something useful"

                    override fun transform(
                        source: Unit,
                        ctx: TransformContext,
                    ) = TransformResult.Success(Unit, TransformTrace())
                }
            TransformerRegistry.register(stub)
            val descs = TransformerRegistry.descriptions()
            descs.keys shouldContainAll listOf("my-transformer")
            descs["my-transformer"] shouldBe "Does something useful"
        }
    })
