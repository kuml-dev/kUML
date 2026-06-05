package dev.kuml.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KumlFormatterTest :
    FunSpec({

        test("removes trailing whitespace from each line") {
            val input = "classOf(\"A\")   \n    attribute(\"id\", \"UUID\")  \n"
            val result = KumlFormatter.format(input)
            result shouldBe "classOf(\"A\")\n    attribute(\"id\", \"UUID\")\n"
        }

        test("collapses multiple consecutive blank lines to one") {
            val input = "line1\n\n\n\nline2\n"
            KumlFormatter.format(input) shouldBe "line1\n\nline2\n"
        }

        test("expands leading tabs to 4 spaces") {
            val input = "\tclassOf(\"A\")\n\t\tattribute(\"id\", \"UUID\")\n"
            KumlFormatter.format(input) shouldBe "    classOf(\"A\")\n        attribute(\"id\", \"UUID\")\n"
        }

        test("ensures single trailing newline and removes trailing blank lines") {
            val input = "diagram(name = \"Test\") { }\n\n\n"
            KumlFormatter.format(input) shouldBe "diagram(name = \"Test\") { }\n"
        }

        test("format is idempotent") {
            val input = "\tdiagram(name = \"Test\") {   \n\n\n\tclassOf(\"A\")   \n}\n\n\n"
            val once = KumlFormatter.format(input)
            val twice = KumlFormatter.format(once)
            twice shouldBe once
        }
    })
