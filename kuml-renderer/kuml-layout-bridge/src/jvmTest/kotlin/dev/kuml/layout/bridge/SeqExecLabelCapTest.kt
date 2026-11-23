package dev.kuml.layout.bridge

import dev.kuml.sysml2.Sysml2Limits
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

class SeqExecLabelCapTest :
    StringSpec({
        "one megabyte name without spaces is capped" {
            val name = "a".repeat(1_000_000)
            wrapExecLabel(name = name).sumOf { it.length } shouldBeLessThanOrEqual Sysml2Limits.TEXT
            seqExecLabelWidth(name) shouldBeLessThanOrEqual Sysml2Limits.TEXT * 6.5f
        }
        "normal names are not shortened" {
            wrapExecLabel(name = "short label here").joinToString(" ") shouldBe "short label here"
        }
    })
