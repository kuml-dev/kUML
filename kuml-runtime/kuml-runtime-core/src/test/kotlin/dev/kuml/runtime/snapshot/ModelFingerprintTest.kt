package dev.kuml.runtime.snapshot

import dev.kuml.runtime.initial
import dev.kuml.runtime.smOf
import dev.kuml.runtime.state
import dev.kuml.runtime.trans
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldMatch

/**
 * Tests für [fingerprint] — stabile, deterministische SHA-256 Fingerprints.
 */
class ModelFingerprintTest :
    FunSpec({
        val baseModel =
            smOf(
                name = "FingerprintSM",
                vertices =
                    listOf(
                        initial("init"),
                        state("A"),
                        state("B"),
                    ),
                transitions =
                    listOf(
                        trans("t1", "init", "A"),
                        trans("t2", "A", "B", trigger = "go"),
                    ),
            )

        test("same model produces same fingerprint") {
            fingerprint(baseModel) shouldBe fingerprint(baseModel)
        }

        test("renaming vertex changes fingerprint") {
            val renamedModel =
                smOf(
                    name = "FingerprintSM",
                    vertices =
                        listOf(
                            initial("init"),
                            state("A-renamed"),
                            state("B"),
                        ),
                    transitions =
                        listOf(
                            trans("t1", "init", "A-renamed"),
                            trans("t2", "A-renamed", "B", trigger = "go"),
                        ),
                )
            fingerprint(baseModel) shouldNotBe fingerprint(renamedModel)
        }

        test("reordering transitions does NOT change fingerprint") {
            // Transitions are sorted before hashing, so order should not matter
            val reorderedModel =
                smOf(
                    name = "FingerprintSM",
                    vertices =
                        listOf(
                            initial("init"),
                            state("A"),
                            state("B"),
                        ),
                    transitions =
                        listOf(
                            // Same transitions but reversed order
                            trans("t2", "A", "B", trigger = "go"),
                            trans("t1", "init", "A"),
                        ),
                )
            fingerprint(baseModel) shouldBe fingerprint(reorderedModel)
        }

        test("adding transition changes fingerprint") {
            val extendedModel =
                smOf(
                    name = "FingerprintSM",
                    vertices =
                        listOf(
                            initial("init"),
                            state("A"),
                            state("B"),
                        ),
                    transitions =
                        listOf(
                            trans("t1", "init", "A"),
                            trans("t2", "A", "B", trigger = "go"),
                            trans("t3", "B", "A", trigger = "back"),
                        ),
                )
            fingerprint(baseModel) shouldNotBe fingerprint(extendedModel)
        }

        test("fingerprint is 32 hex characters") {
            val fp = fingerprint(baseModel)
            fp shouldHaveLength 32
            fp shouldMatch Regex("[0-9a-f]+")
        }
    })
