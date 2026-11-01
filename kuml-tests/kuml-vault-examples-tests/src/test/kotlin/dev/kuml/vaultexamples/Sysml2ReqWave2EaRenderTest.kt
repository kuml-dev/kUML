package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Wave 2E-a - renders the requirement fixtures and the REQ vault examples for visual inspection. */
class Sysml2ReqWave2EaRenderTest :
    FunSpec({
        for (n in listOf("req-grid", "req-grid-subject", "req-grid-right", "req-grid-aspect")) {
            test("render $n without error") {
                val script =
                    Sysml2ReqWave2EaRenderTest::class.java
                        .getResourceAsStream(
                            "/sysml2-e2a/$n.kuml.kts",
                        )!!
                        .bufferedReader()
                        .readText()
                val r = VaultExampleRenderer.render(script = script)
                r.error shouldBe null
                SampleOutput.write(filename = "sysml2-e2a/$n.svg", content = r.svg!!)
            }
        }
    })
