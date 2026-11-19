package dev.kuml.desktop.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TrustDialogSanitizeTest :
    FunSpec({
        test("control and bidi format characters are shown as visible escapes") {
            sanitizeForDisplay("/tmp/a\nb\u202Ec") shouldBe "/tmp/a\\u000Ab\\u202Ec"
            sanitizeForDisplay("/tmp/clean dir/ü") shouldBe "/tmp/clean dir/ü"
        }

        test("line and paragraph separators are shown as visible escapes") {
            sanitizeForDisplay("/tmp/a\u2028b\u2029c") shouldBe "/tmp/a\\u2028b\\u2029c"
        }

        test("supplementary-plane tag characters are escaped as one code point") {
            sanitizeForDisplay("/tmp/a" + String(Character.toChars(0xE0041)) + "b") shouldBe "/tmp/a\\u{E0041}b"
            // a clean supplementary character (emoji) stays untouched
            sanitizeForDisplay("/tmp/\uD83D\uDE00") shouldBe "/tmp/\uD83D\uDE00"
        }
    })
