package dev.kuml.io.latex.note

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Output without notes must stay byte-identical to the reference taken before the note support existed
 * (TeX source text, so independent of fonts). The references live in `src/test/resources/golden/no-notes/`.
 * Set `KUML_GOLDEN_WRITE=1` to regenerate them (only on a state that is known to be correct).
 */
class NoNotesGoldenTest :
    StringSpec({
        val cases: Map<String, () -> String> =
            mapOf(
                "uml-class.tex" to { NoteFixtures.renderUml() },
                "uml-class-standalone.tex" to { NoteFixtures.renderUml(standalone = true) },
                "sysml2-bdd.tex" to { NoteFixtures.renderBdd() },
                "sysml2-stm.tex" to { NoteFixtures.renderStm() },
                "c4-context.tex" to { NoteFixtures.renderC4() },
            )
        val write = System.getenv("KUML_GOLDEN_WRITE") == "1"
        val dir = Paths.get(System.getProperty("user.dir")).resolve("src/test/resources/golden/no-notes")
        for ((name, render) in cases) {
            "no-notes golden: $name" {
                val actual = render()
                if (write) {
                    Files.createDirectories(dir)
                    Files.writeString(dir.resolve(name), actual)
                }
                val expected =
                    NoNotesGoldenTest::class.java
                        .getResourceAsStream("/golden/no-notes/$name")
                        ?.readBytes()
                        ?.toString(Charsets.UTF_8)
                        ?: error("missing golden $name (run once with KUML_GOLDEN_WRITE=1, then re-run)")
                actual shouldBe expected
            }
        }
    })
