package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Wave 2I-b - renders the analysis / calculation / perform / exhibit fixtures (output under build/sample-output/wave-2ib). */
class Sysml2Wave2IbRenderTest :
    FunSpec({
        for (n in listOf("analysis-calc", "part-exhibit-perform")) {
            test("render $n without error") {
                val script =
                    Sysml2Wave2IbRenderTest::class.java
                        .getResourceAsStream("/wave-2ib/$n.kuml.kts")!!
                        .bufferedReader()
                        .readText()
                val r = VaultExampleRenderer.render(script = script)
                r.error shouldBe null
                SampleOutput.write(filename = "wave-2ib/$n.svg", content = r.svg!!)
            }
        }
    })
