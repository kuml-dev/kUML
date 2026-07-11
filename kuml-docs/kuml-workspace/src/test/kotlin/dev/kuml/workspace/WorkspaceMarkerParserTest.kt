package dev.kuml.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WorkspaceMarkerParserTest :
    FunSpec({

        test("parses the demo marker: mode=knowledge, okfVersion=0.1") {
            val toml =
                """
                |[workspace]
                |mode = "knowledge"
                |
                |[okf]
                |version = "0.1"
                """.trimMargin()
            val marker = WorkspaceMarkerParser.parse(toml)
            marker.mode shouldBe WorkspaceMode.KNOWLEDGE
            marker.okfVersion shouldBe "0.1"
            marker.name shouldBe null
        }

        test("parses quoted name and kuml-version") {
            val toml =
                """
                |[workspace]
                |mode = "engineering"
                |name = "PZB Domain Model"
                |kuml-version = ">=0.30.0"
                """.trimMargin()
            val marker = WorkspaceMarkerParser.parse(toml)
            marker.mode shouldBe WorkspaceMode.ENGINEERING
            marker.name shouldBe "PZB Domain Model"
            marker.kumlVersion shouldBe ">=0.30.0"
        }

        test("ignores comment lines") {
            val toml =
                """
                |# top-level comment
                |[workspace]
                |# mode comment
                |mode = "knowledge"
                """.trimMargin()
            val marker = WorkspaceMarkerParser.parse(toml)
            marker.mode shouldBe WorkspaceMode.KNOWLEDGE
        }

        test("missing [okf] section yields null okf fields") {
            val toml =
                """
                |[workspace]
                |mode = "knowledge"
                """.trimMargin()
            val marker = WorkspaceMarkerParser.parse(toml)
            marker.okfVersion shouldBe null
            marker.vocabulary shouldBe null
            marker.strict shouldBe null
        }

        test("parses strict = true") {
            val toml =
                """
                |[workspace]
                |mode = "knowledge"
                |
                |[okf]
                |version = "0.1"
                |strict = true
                """.trimMargin()
            val marker = WorkspaceMarkerParser.parse(toml)
            marker.strict shouldBe true
        }

        test("parses strict = false") {
            val toml =
                """
                |[okf]
                |strict = false
                """.trimMargin()
            val marker = WorkspaceMarkerParser.parse(toml)
            marker.strict shouldBe false
        }

        test("unknown section is ignored, mode stays UNKNOWN") {
            val toml =
                """
                |[future-section]
                |mode = "knowledge"
                """.trimMargin()
            val marker = WorkspaceMarkerParser.parse(toml)
            marker.mode shouldBe WorkspaceMode.UNKNOWN
        }

        test("empty input yields all-null/UNKNOWN marker, never throws") {
            val marker = WorkspaceMarkerParser.parse("")
            marker.mode shouldBe WorkspaceMode.UNKNOWN
            marker.name shouldBe null
            marker.okfVersion shouldBe null
            marker.strict shouldBe null
        }

        test("parses [okf] vocabulary field") {
            val toml =
                """
                |[okf]
                |vocabulary = "dev.kuml.okf@1.0"
                """.trimMargin()
            val marker = WorkspaceMarkerParser.parse(toml)
            marker.vocabulary shouldBe "dev.kuml.okf@1.0"
        }
    })
