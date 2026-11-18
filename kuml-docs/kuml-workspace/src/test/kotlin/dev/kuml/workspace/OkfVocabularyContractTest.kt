package dev.kuml.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
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
    val aliases: List<String> = emptyList(),
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
                    VocabEntry(
                        id = it.id,
                        requiresKumlBlock = it.requiresKumlBlock,
                        since = it.since,
                        description = it.description,
                        aliases = it.aliases.sorted(),
                    )
                }
            doc.types.map { it.copy(aliases = it.aliases.sorted()) } shouldContainExactlyInAnyOrder fromEnum
        }

        test("JSON entry count matches OkfType.entries count") {
            doc.types.size shouldBe OkfType.entries.size
        }

        test("vocabulary lists Sysml2PackageDiagram and UmlStateMachine") {
            val ids = doc.types.map { it.id }
            ids shouldContain "Sysml2PackageDiagram"
            ids shouldContain "UmlStateMachine"
            OkfType.fromId("Sysml2PackageDiagram")?.requiresKumlBlock shouldBe true
        }

        test("ids and aliases are unique and no alias equals a canonical id") {
            val ids = OkfType.entries.map { it.id }
            val aliases = OkfType.entries.flatMap { it.aliases }
            ids.toSet().size shouldBe ids.size
            aliases.toSet().size shouldBe aliases.size
            aliases.filter { it in ids } shouldBe emptyList()
        }

        test("every alias resolves to its owning type") {
            OkfType.entries.forEach { t -> t.aliases.forEach { OkfType.fromId(it) shouldBe t } }
        }

        test("isAlias and canonicalIdFor") {
            OkfType.isAlias("Sysml2Bdd") shouldBe true
            OkfType.isAlias("Sysml2BlockDefinition") shouldBe false
            OkfType.isAlias(null) shouldBe false
            OkfType.canonicalIdFor("Sysml2Bdd") shouldBe "Sysml2BlockDefinition"
            OkfType.canonicalIdFor("Sysml2BlockDefinition") shouldBe "Sysml2BlockDefinition"
            OkfType.canonicalIdFor("Nope") shouldBe null
        }

        test("alias matching is case-sensitive") {
            OkfType.fromId("sysml2bdd") shouldBe null
        }

        test("the three new C4 types are since 1.1") {
            listOf("C4DeploymentDiagram", "C4DynamicDiagram", "C4LandscapeDiagram").forEach {
                OkfType.fromId(it)?.since shouldBe "1.1"
                OkfType.fromId(it)?.requiresKumlBlock shouldBe true
            }
        }
    })
