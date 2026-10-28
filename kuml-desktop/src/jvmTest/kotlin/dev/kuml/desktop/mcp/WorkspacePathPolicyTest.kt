package dev.kuml.desktop.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WorkspacePathPolicyTest :
    FunSpec({

        fun reason(raw: String): String? = (WorkspacePathPolicy.check(raw) as? WorkspacePathPolicy.Checked.Rejected)?.reason

        test("rejects every path shape that must never reach the file system") {
            val cases =
                mapOf(
                    "" to "EMPTY",
                    "   " to "EMPTY",
                    "/etc/passwd" to "ABSOLUTE_PATH",
                    "\\abs.md" to "ABSOLUTE_PATH",
                    "~/x.md" to "ABSOLUTE_PATH",
                    "//host/share/x.md" to "UNC_PATH",
                    "\\\\host\\share\\x.md" to "UNC_PATH",
                    "C:/x.md" to "DRIVE_LETTER",
                    "c:x.md" to "DRIVE_LETTER",
                    "..\\x.md" to "PARENT_SEGMENT",
                    "../x.md" to "PARENT_SEGMENT",
                    "a/../b.md" to "PARENT_SEGMENT",
                    "a/./b.md" to "PARENT_SEGMENT",
                    "a//b.md" to "INVALID_NAME",
                    "a/b.md/" to "INVALID_NAME",
                    "a\u0000b.md" to "INVALID_NAME",
                    "a\nb.md" to "INVALID_NAME",
                    ("a/".repeat(600) + "x.md") to "TOO_LONG",
                )
            cases.forEach { (raw, expected) -> reason(raw) shouldBe expected }
        }

        test("accepts relative paths and normalises backslashes") {
            (WorkspacePathPolicy.check("models/a.md") as WorkspacePathPolicy.Checked.Ok).posixPath shouldBe "models/a.md"
            (WorkspacePathPolicy.check("models\\sub\\a.md") as WorkspacePathPolicy.Checked.Ok).posixPath shouldBe "models/sub/a.md"
        }

        test("a fullwidth full stop is not a parent segment (but also cannot traverse)") {
            reason("\uFF0E\uFF0E/x.md") shouldBe null
        }

        test("sanitize strips control characters and bounds the length") {
            WorkspacePathPolicy.sanitize("a\u0000b\nc") shouldBe "a?b?c"
            WorkspacePathPolicy.sanitize("x".repeat(500)).length shouldBe 120
        }
    })
