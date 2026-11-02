package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Wave 2I-a - renders the package-diagram fixtures for visual inspection (output under build/sample-output/wave-2ia). */
class Sysml2Wave2IaRenderTest :
    FunSpec({
        for (n in listOf("nested-packages-imports", "view-viewpoint")) {
            test("render $n without error") {
                val script =
                    Sysml2Wave2IaRenderTest::class.java
                        .getResourceAsStream("/wave-2ia/$n.kuml.kts")!!
                        .bufferedReader()
                        .readText()
                val r = VaultExampleRenderer.render(script = script)
                r.error shouldBe null
                SampleOutput.write(filename = "wave-2ia/$n.svg", content = r.svg!!)
            }
        }
    })
