package dev.kuml.codegen.m2m

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class TransformChainTest :
    FunSpec(body = {

        /** A transformer that doubles an integer. */
        val doubler =
            object : KumlTransformer<Int, Int> {
                override val id = "doubler"
                override val description = "Doubles input"

                override fun transform(
                    source: Int,
                    ctx: TransformContext,
                ): TransformResult<Int> =
                    TransformResult.Success(
                        output = source * 2,
                        trace =
                            TransformTrace(
                                listOf(
                                    TraceabilityLink(sourceElementId = "src-$source", targetArtifactId = "doubled", ruleId = "double-rule"),
                                ),
                            ),
                    )
            }

        /** A transformer that converts an int to a string. */
        val intToStr =
            object : KumlTransformer<Int, String> {
                override val id = "int-to-str"
                override val description = "Int to string"

                override fun transform(
                    source: Int,
                    ctx: TransformContext,
                ): TransformResult<String> =
                    TransformResult.Success(
                        output = source.toString(),
                        trace =
                            TransformTrace(
                                listOf(TraceabilityLink(sourceElementId = "num-$source", targetArtifactId = "str", ruleId = "str-rule")),
                            ),
                    )
            }

        /** A transformer that always fails. */
        val alwaysFail =
            object : KumlTransformer<Int, Int> {
                override val id = "always-fail"
                override val description = "Always fails"

                override fun transform(
                    source: Int,
                    ctx: TransformContext,
                ): TransformResult<Int> =
                    TransformResult.Failure(listOf(TransformError(message = "Simulated failure", elementId = "elem-$source")))
            }

        test("two-step chain threads trace from both steps") {
            val chain = TransformChain(first = doubler, second = intToStr)
            val result = chain.transform(source = 5, ctx = TransformContext())

            result.shouldBeInstanceOf<TransformResult.Success<String>>()
            result.output shouldBe "10"
            result.trace.links shouldHaveSize 2
            result.trace.links[0].ruleId shouldBe "double-rule"
            result.trace.links[1].ruleId shouldBe "str-rule"
        }

        test("chain id is composed of first+second ids") {
            val chain = TransformChain(first = doubler, second = intToStr)
            chain.id shouldBe "doubler+int-to-str"
        }

        test("failure in first step propagates, second step is not called") {
            var secondCalled = false
            val second =
                object : KumlTransformer<Int, String> {
                    override val id = "second"
                    override val description = "Second step"

                    override fun transform(
                        source: Int,
                        ctx: TransformContext,
                    ): TransformResult<String> {
                        secondCalled = true
                        return TransformResult.Success(output = source.toString(), trace = TransformTrace())
                    }
                }

            val chain = TransformChain(first = alwaysFail, second = second)
            val result = chain.transform(source = 42, ctx = TransformContext())

            result.shouldBeInstanceOf<TransformResult.Failure>()
            secondCalled shouldBe false
            result.errors[0].message shouldBe "Simulated failure"
        }

        test("failure in second step propagates after first step ran successfully") {
            var firstRan = false
            val first =
                object : KumlTransformer<Int, Int> {
                    override val id = "first"
                    override val description = "First step"

                    override fun transform(
                        source: Int,
                        ctx: TransformContext,
                    ): TransformResult<Int> {
                        firstRan = true
                        return TransformResult.Success(output = source * 2, trace = TransformTrace())
                    }
                }

            val failingSecond =
                object : KumlTransformer<Int, String> {
                    override val id = "failing-second"
                    override val description = "Failing second"

                    override fun transform(
                        source: Int,
                        ctx: TransformContext,
                    ): TransformResult<String> = TransformResult.Failure(listOf(TransformError(message = "Second step failed")))
                }

            val chain = TransformChain(first = first, second = failingSecond)
            val result = chain.transform(source = 3, ctx = TransformContext())

            firstRan shouldBe true
            result.shouldBeInstanceOf<TransformResult.Failure>()
            result.errors[0].message shouldBe "Second step failed"
        }
    })
