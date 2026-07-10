package dev.kuml.uml

import dev.kuml.core.model.KumlMetaValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TransitionExtensionsTest :
    FunSpec(body = {

        val json =
            Json {
                prettyPrint = true
                encodeDefaults = true
            }

        fun transition(metadata: Map<String, KumlMetaValue> = emptyMap()): UmlTransition =
            UmlTransition(
                id = "t1",
                sourceId = "A",
                targetId = "B",
                metadata = metadata,
            )

        test(name = "isProtected is false for a transition with empty metadata") {
            transition().isProtected.shouldBeFalse()
        }

        test(name = "isProtected is true when metadata[\"protected\"] = Flag(true)") {
            transition(mapOf(TransitionMetadataKeys.PROTECTED to KumlMetaValue.Flag(true))).isProtected.shouldBeTrue()
        }

        test(name = "isProtected is false when the flag is Flag(false)") {
            transition(mapOf(TransitionMetadataKeys.PROTECTED to KumlMetaValue.Flag(false))).isProtected.shouldBeFalse()
        }

        test(name = "isProtected is false when the key holds a non-Flag value") {
            transition(mapOf(TransitionMetadataKeys.PROTECTED to KumlMetaValue.Text("true"))).isProtected.shouldBeFalse()
        }

        test(name = "protected transition survives a JSON round-trip") {
            val before = transition(mapOf(TransitionMetadataKeys.PROTECTED to KumlMetaValue.Flag(true)))
            val text = json.encodeToString(before)
            val after = json.decodeFromString<UmlTransition>(text)
            after.isProtected.shouldBeTrue()
        }
    })
