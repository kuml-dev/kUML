package dev.kuml.sysml2

import dev.kuml.sysml2.constraint.Sysml2SeqChecker
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.edge.UcEdgeAdapter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Regression tests for the optional (nullable) text fields `TimeObservationUsage.label`,
 * `UcExtend.condition` and `UcExtend.extensionPoint`: JSON round-trip with both `encodeDefaults`
 * variants, explicit `null`, printer and checker behaviour. Also documents the strict/tolerant
 * JSON property (unknown keys) of the metamodel. No behaviour change.
 */
class Sysml2NullableFieldRoundtripTest :
    FunSpec({
        val jsonDefaultsOn = Json { encodeDefaults = true }
        val jsonDefaultsOff = Json { encodeDefaults = false }
        val variants = listOf("encodeDefaults=true" to jsonDefaultsOn, "encodeDefaults=false" to jsonDefaultsOff)

        fun obs(label: String?) = TimeObservationUsage(id = "t", name = "t", lifelineId = "a", seqNo = 0, label = label)

        fun ext(
            condition: String?,
            point: String?,
        ) = UcExtend(id = "e", sourceUseCaseId = "a", targetUseCaseId = "b", condition = condition, extensionPoint = point)

        for ((variantName, json) in variants) {
            for (label in listOf<String?>(null, "", "   ", "now")) {
                test("TimeObservationUsage label=${label?.let { "'$it'" }} survives a JSON round-trip ($variantName)") {
                    val o = obs(label)
                    json.decodeFromString(
                        TimeObservationUsage.serializer(),
                        json.encodeToString(TimeObservationUsage.serializer(), o),
                    ) shouldBe o
                }
            }
            for (v in listOf<String?>(null, "", "  ", "x")) {
                test("UcExtend condition/extensionPoint=${v?.let { "'$it'" }} survives a JSON round-trip ($variantName)") {
                    val e = ext(v, v)
                    json.decodeFromString(UcExtend.serializer(), json.encodeToString(UcExtend.serializer(), e)) shouldBe e
                }
            }
        }

        test("explicit JSON null and a missing key both decode to null") {
            val j = Json
            j
                .decodeFromString(
                    TimeObservationUsage.serializer(),
                    """{"id":"t","name":"t","lifelineId":"a","seqNo":0,"label":null}""",
                ).label shouldBe null
            j
                .decodeFromString(
                    TimeObservationUsage.serializer(),
                    """{"id":"t","name":"t","lifelineId":"a","seqNo":0}""",
                ).label shouldBe null
            val e =
                j.decodeFromString(
                    UcExtend.serializer(),
                    """{"id":"e","sourceUseCaseId":"a","targetUseCaseId":"b","condition":null,"extensionPoint":null}""",
                )
            e.condition shouldBe null
            e.extensionPoint shouldBe null
            j.decodeFromString(
                UcExtend.serializer(),
                """{"id":"e","sourceUseCaseId":"a","targetUseCaseId":"b"}""",
            ) shouldBe ext(null, null)
        }

        test("printer omits null label / condition / extensionPoint and checker does not complain") {
            val model =
                sysml2Model(name = "N") {
                    val a = lifelineDef(name = "a")
                    timeObservation(lifeline = a, seqNo = 0, name = "tNull")
                    timeObservation(lifeline = a, seqNo = 1, name = "tEmpty", label = "")
                    seqDiagram(name = "S") { include(a) }
                    val u1 = useCaseDef(name = "U1")
                    val u2 = useCaseDef(name = "U2")
                    ucDiagram(name = "UC") {
                        include(u1)
                        include(u2)
                        extend(source = u1, target = u2)
                    }
                }
            val printed = Sysml2DslPrinter.print(model)
            printed.lines().first { it.contains("tNull") } shouldNotContain "label ="
            printed shouldNotContain "condition ="
            printed shouldNotContain "extensionPoint ="
            printed shouldNotContain "null"
            Sysml2SeqChecker
                .check(model = model, diagram = model.diagrams.filterIsInstance<SeqDiagram>().single())
                .none { it.code == 411 } shouldBe true
        }

        test("extend edge metadata has no label for null, empty and blank conditions") {
            for (c in listOf<String?>(null, "", "   ")) {
                UcEdgeAdapter.extendMetadata(ext(c, null)).label shouldBe null
            }
            UcEdgeAdapter.extendMetadata(ext("fee", null)).label shouldBe "[fee]"
        }

        test("unknown JSON keys: strict instance throws, tolerant instance drops the key on re-encode (documented property)") {
            val withExtra = """{"id":"t","name":"t","lifelineId":"a","seqNo":0,"futureField":"x"}"""
            shouldThrow<SerializationException> {
                Json.decodeFromString(TimeObservationUsage.serializer(), withExtra)
            }
            val tolerant = Json { ignoreUnknownKeys = true }
            val decoded = tolerant.decodeFromString(TimeObservationUsage.serializer(), withExtra)
            decoded shouldBe obs(null)
            tolerant.encodeToString(TimeObservationUsage.serializer(), decoded) shouldNotContain "futureField"
            tolerant.encodeToString(TimeObservationUsage.serializer(), decoded) shouldContain "lifelineId"
        }
    })
