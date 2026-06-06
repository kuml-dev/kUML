package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class VersionTest :
    FunSpec({

        test("KumlVersion loads the version from the bundled properties file") {
            // Build's `processResources` filter replaces `@version@` with the
            // Gradle project version. In a clean test environment that's the
            // current snapshot string; we only assert it's been substituted
            // (no leftover `@…@` placeholder).
            KumlVersion.version shouldNotContain "@version@"
            KumlVersion.version.length shouldBe KumlVersion.version.trim().length
            // Format constraint: SemVer-ish — digit, dot, digit at minimum.
            KumlVersion.version shouldMatch Regex("""\d+\.\d+(\.\d+)?(-[A-Za-z0-9.+-]+)?""")
        }

        test("KumlVersion exposes a gitSha placeholder or a real short hash") {
            // In CI the build runs inside a git checkout — gitSha is the short
            // SHA. Locally outside a git tree, the fallback "unknown" applies.
            // Either is acceptable; the contract is "non-empty, not the raw
            // placeholder".
            KumlVersion.gitSha shouldNotContain "@gitSha@"
            KumlVersion.gitSha.isNotBlank() shouldBe true
        }

        test("KumlVersion.formatPlain follows the kubectl/gh/brew convention") {
            val plain = KumlVersion.formatPlain()
            plain shouldContain "kuml "
            plain shouldContain "(build: "
            plain shouldContain "jdk: "
        }

        test("kuml --version prints the plain format and exits 0") {
            val result = KumlCli().test("--version")
            result.statusCode shouldBe 0
            result.stdout shouldContain "kuml "
            result.stdout shouldContain "build: "
            result.stdout shouldContain "jdk: "
            // versionOption-driven output uses no surprise leading whitespace.
            result.stdout.trim().startsWith("kuml ") shouldBe true
        }

        test("kuml version (no flag) prints the same plain text as --version") {
            val flagOut = KumlCli().test("--version").stdout.trim()
            val cmdOut = KumlCli().test("version").stdout.trim()
            cmdOut shouldBe flagOut
        }

        test("kuml version --json emits a valid JSON object with all four fields") {
            val result = KumlCli().test("version --json")
            result.statusCode shouldBe 0
            val parsed = Json.parseToJsonElement(result.stdout.trim()) as JsonObject
            parsed["version"]?.jsonPrimitive?.content shouldBe KumlVersion.version
            parsed["gitSha"]?.jsonPrimitive?.content shouldBe KumlVersion.gitSha
            parsed["jdk"]?.jsonPrimitive?.content shouldBe KumlVersion.jdkVersion
            // buildTime is build-stamped; we only assert it's present and non-empty
            (parsed["buildTime"]?.jsonPrimitive?.content?.isNotBlank() ?: false) shouldBe true
        }
    })
