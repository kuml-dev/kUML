package dev.kuml.cli.determinism

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

class DeterminismSameJvmTest :
    FunSpec({
        test("rendering every fixture twice in the same JVM yields identical SVG bytes") {
            val first = DeterminismHarness.renderAll()
            first.shouldNotBeEmpty()
            val errors = first.filter { it.second.startsWith("ERROR:") }
            println("determinism fixtures=${first.size} errors=${errors.size} ${errors.map { it.first + '=' + it.second }}")
            // Fixtures that fail to render would trivially compare equal; keep them a small minority.
            (errors.size * 4 < first.size) shouldBe true
            val second = DeterminismHarness.renderAll()
            second.size shouldBe first.size
            val diffs = first.zip(second).filter { (a, b) -> a != b }.map { it.first.first }
            diffs shouldBe emptyList()
        }
    })
