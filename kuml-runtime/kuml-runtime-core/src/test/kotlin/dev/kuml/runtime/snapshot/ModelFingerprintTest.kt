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
                        state(id = "A"),
                        state(id = "B"),
                    ),
                transitions =
                    listOf(
                        trans(id = "t1", from = "init", to = "A"),
                        trans(id = "t2", from = "A", to = "B", trigger = "go"),
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
                            state(id = "A-renamed"),
                            state(id = "B"),
                        ),
                    transitions =
                        listOf(
                            trans(id = "t1", from = "init", to = "A-renamed"),
                            trans(id = "t2", from = "A-renamed", to = "B", trigger = "go"),
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
                            state(id = "A"),
                            state(id = "B"),
                        ),
                    transitions =
                        listOf(
                            // Same transitions but reversed order
                            trans(id = "t2", from = "A", to = "B", trigger = "go"),
                            trans(id = "t1", from = "init", to = "A"),
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
                            state(id = "A"),
                            state(id = "B"),
                        ),
                    transitions =
                        listOf(
                            trans(id = "t1", from = "init", to = "A"),
                            trans(id = "t2", from = "A", to = "B", trigger = "go"),
                            trans(id = "t3", from = "B", to = "A", trigger = "back"),
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
