package dev.kuml.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class VocabEntry(
    val id: String,
    val requiresKumlBlock: Boolean,
    val since: String,
    val description: String,
)

@Serializable
private data class VocabDocument(
    val vocabulary: String,
    val version: String,
    val okfVersion: String,
    val types: List<VocabEntry>,
)

/**
 * Bijective parity check between [OkfType.entries] and the checked-in
 * `/okf/kuml-okf-vocabulary.json` resource — this is exactly the artifact FT-8
 * later lifts out into the standalone `kuml-dev/kuml-okf-types` distribution.
 *
 * Any drift (a value added/renamed/removed on one side but not the other, or a
 * property mismatch) fails loudly here rather than surfacing downstream.
 */
class OkfVocabularyContractTest :
    FunSpec({

        val json = Json { ignoreUnknownKeys = true }
        val resourceText =
            requireNotNull(OkfType::class.java.getResourceAsStream("/okf/kuml-okf-vocabulary.json")) {
                "Missing classpath resource /okf/kuml-okf-vocabulary.json"
            }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val doc = json.decodeFromString<VocabDocument>(resourceText)

        test("top-level version matches OkfType.VOCABULARY_VERSION") {
            doc.version shouldBe OkfType.VOCABULARY_VERSION
        }

        test("every OkfType entry has exactly one matching JSON entry") {
            val fromEnum =
                OkfType.entries.map {
                    VocabEntry(id = it.id, requiresKumlBlock = it.requiresKumlBlock, since = it.since, description = it.description)
                }
            doc.types shouldContainExactlyInAnyOrder fromEnum
        }

        test("JSON entry count matches OkfType.entries count") {
            doc.types.size shouldBe OkfType.entries.size
        }
    })
