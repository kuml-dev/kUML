package dev.kuml.runtime.chain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class ModelHasherTest :
    StringSpec({
        // ── canonicalize ──────────────────────────────────────────────────────────

        "canonicalize: LF line endings are preserved unchanged" {
            val input = "model {\n    state(\"A\")\n}\n"
            ModelHasher.canonicalize(input) shouldBe input
        }

        "canonicalize: CRLF is normalised to LF" {
            val crlf = "model {\r\n    state(\"A\")\r\n}\r\n"
            val lf = "model {\n    state(\"A\")\n}\n"
            ModelHasher.canonicalize(crlf) shouldBe lf
        }

        "canonicalize: CR-only is normalised to LF" {
            val cr = "model {\r    state(\"A\")\r}\r"
            val lf = "model {\n    state(\"A\")\n}\n"
            ModelHasher.canonicalize(cr) shouldBe lf
        }

        "canonicalize: trailing whitespace removed from each line" {
            val withTrailing = "model {   \n    state(\"A\")  \n}  \n"
            val clean = "model {\n    state(\"A\")\n}\n"
            ModelHasher.canonicalize(withTrailing) shouldBe clean
        }

        "canonicalize: leading tabs expanded to 4 spaces" {
            val tabbed = "model {\n\tstate(\"A\")\n\t\tnested()\n}\n"
            val spaced = "model {\n    state(\"A\")\n        nested()\n}\n"
            ModelHasher.canonicalize(tabbed) shouldBe spaced
        }

        "canonicalize: ALL blank lines removed (stricter than fmt)" {
            val withBlanks = "model {\n\n    state(\"A\")\n\n\n    state(\"B\")\n\n}\n"
            val noBlanks = "model {\n    state(\"A\")\n    state(\"B\")\n}\n"
            ModelHasher.canonicalize(withBlanks) shouldBe noBlanks
        }

        "canonicalize: whitespace-only input normalises to single LF" {
            ModelHasher.canonicalize("   \n\n\t\n") shouldBe "\n"
        }

        "canonicalize: empty string normalises to single LF" {
            ModelHasher.canonicalize("") shouldBe "\n"
        }

        "canonicalize: is idempotent — apply twice gives same result" {
            val inputs =
                listOf(
                    "model { state(\"A\") }",
                    "\t\tmodel {\r\n\r\n\tstate(\"X\")  \r\n}\r\n",
                    "",
                    "   ",
                    "a\n\n\nb\n\nc\n",
                )
            for (input in inputs) {
                val once = ModelHasher.canonicalize(input)
                val twice = ModelHasher.canonicalize(once)
                twice shouldBe once
            }
        }

        // ── hashCanonical — Whitespace-Permutation-Stabilität ─────────────────────

        "hashCanonical: tab-indented and space-indented equivalent produce identical hash" {
            val tabIndented = "model {\n\tstate(\"A\")\n\tstate(\"B\")\n}\n"
            val spaceIndented = "model {\n    state(\"A\")\n    state(\"B\")\n}\n"
            val h1 = ModelHasher.hashCanonical(ModelHasher.canonicalize(tabIndented))
            val h2 = ModelHasher.hashCanonical(ModelHasher.canonicalize(spaceIndented))
            h1.contentEquals(h2).shouldBeTrue()
        }

        "hashCanonical: trailing whitespace variations produce identical hash" {
            val clean = "model {\n    state(\"A\")\n}\n"
            val dirty = "model {   \n    state(\"A\")  \n}   \n"
            val h1 = ModelHasher.hashCanonical(ModelHasher.canonicalize(clean))
            val h2 = ModelHasher.hashCanonical(ModelHasher.canonicalize(dirty))
            h1.contentEquals(h2).shouldBeTrue()
        }

        "hashCanonical: blank-line-count variations produce identical hash" {
            val zero = "model {\n    state(\"A\")\n}\n"
            val one = "model {\n\n    state(\"A\")\n\n}\n"
            val three = "model {\n\n\n\n    state(\"A\")\n\n\n\n}\n"
            val h0 = ModelHasher.hashCanonical(ModelHasher.canonicalize(zero))
            val h1 = ModelHasher.hashCanonical(ModelHasher.canonicalize(one))
            val h3 = ModelHasher.hashCanonical(ModelHasher.canonicalize(three))
            h0.contentEquals(h1).shouldBeTrue()
            h0.contentEquals(h3).shouldBeTrue()
        }

        "hashCanonical: CRLF vs LF produce identical hash" {
            val lf = "model {\n    state(\"A\")\n}\n"
            val crlf = "model {\r\n    state(\"A\")\r\n}\r\n"
            val h1 = ModelHasher.hashCanonical(ModelHasher.canonicalize(lf))
            val h2 = ModelHasher.hashCanonical(ModelHasher.canonicalize(crlf))
            h1.contentEquals(h2).shouldBeTrue()
        }

        "hashCanonical: empty/whitespace-only input produces stable defined hash" {
            val empty = ModelHasher.hashCanonical(ModelHasher.canonicalize(""))
            val whitespaceOnly = ModelHasher.hashCanonical(ModelHasher.canonicalize("   \n\n\t"))
            // Both normalise to "\n", so hashes must be identical.
            empty.contentEquals(whitespaceOnly).shouldBeTrue()
            // Hash must be 32 bytes (SHA-256).
            empty.size shouldBe 32
        }

        "hashCanonical: different meaningful content produces different hash" {
            val modelA = ModelHasher.hashCanonical(ModelHasher.canonicalize("model { state(\"A\") }\n"))
            val modelB = ModelHasher.hashCanonical(ModelHasher.canonicalize("model { state(\"B\") }\n"))
            modelA.contentEquals(modelB) shouldBe false
        }

        "hashCanonical: returns 32 bytes (SHA-256)" {
            val hash = ModelHasher.hashCanonical("some canonical form\n")
            hash.size shouldBe 32
        }

        // ── hashTransitive ────────────────────────────────────────────────────────

        "hashTransitive: without imports equals simple canonical hash" {
            val script = "model {\n    state(\"A\")\n}\n"
            val transitive = ModelHasher.hashTransitive(script) { error("should not be called") }
            val simple =
                run {
                    val d = java.security.MessageDigest.getInstance("SHA-256")
                    val canonical = ModelHasher.canonicalize(script)
                    // Sentinel URI "" + content + separators
                    d.update("".toByteArray(Charsets.UTF_8))
                    d.update(0)
                    d.update(canonical.toByteArray(Charsets.UTF_8))
                    d.update(1)
                    d.digest()
                }
            transitive.contentEquals(simple).shouldBeTrue()
        }

        "hashTransitive: is deterministic — same input always produces same hash" {
            // Verifies sortedMapOf ensures stable ordering regardless of JVM hash-map order.
            val script = "// root\nimport(\"a.kuml\")\nimport(\"b.kuml\")\n"
            val resolver: (String) -> String = { uri ->
                when (uri) {
                    "a.kuml" -> "// module A\n"
                    "b.kuml" -> "// module B\n"
                    else -> error("unexpected uri: $uri")
                }
            }
            val h1 = ModelHasher.hashTransitive(script, resolver)
            val h2 = ModelHasher.hashTransitive(script, resolver)
            h1.contentEquals(h2).shouldBeTrue()
            h1.size shouldBe 32
        }

        "hashTransitive: terminates on cyclic imports (A imports B, B imports A)" {
            val resolver: (String) -> String = { uri ->
                when (uri) {
                    "a.kuml" -> "import(\"b.kuml\")\n// A\n"
                    "b.kuml" -> "import(\"a.kuml\")\n// B\n"
                    else -> error("unexpected: $uri")
                }
            }
            // Must not throw StackOverflowError or loop forever.
            val hash = ModelHasher.hashTransitive("import(\"a.kuml\")\n// root\n", resolver)
            hash.size shouldBe 32
        }

        "hashTransitive: different import content produces different hash" {
            val script = "import(\"lib.kuml\")\n// main\n"
            val hV1 = ModelHasher.hashTransitive(script) { "// library v1\n" }
            val hV2 = ModelHasher.hashTransitive(script) { "// library v2\n" }
            hV1.contentEquals(hV2) shouldBe false
        }

        "hashTransitive: duplicate import URIs do not cause double-hashing — terminates with valid 32-byte hash" {
            // A script that imports the same URI twice must not visit it twice.
            // Observable behavior: the function terminates and returns a deterministic 32-byte hash.
            val script = "import(\"lib.kuml\")\nimport(\"lib.kuml\")\n// main\n"
            val resolver: (String) -> String = { "// shared lib content\n" }
            val h1 = ModelHasher.hashTransitive(script, resolver)
            val h2 = ModelHasher.hashTransitive(script, resolver)
            h1.size shouldBe 32
            h1.contentEquals(h2).shouldBeTrue() // deterministic across calls
        }
    })

// ── helpers ───────────────────────────────────────────────────────────────────

/** Converts a byte array to lowercase hex string. */
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
