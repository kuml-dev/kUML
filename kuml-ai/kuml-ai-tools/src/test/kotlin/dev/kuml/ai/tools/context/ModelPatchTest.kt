package dev.kuml.ai.tools.context

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import kotlinx.serialization.json.Json

class ModelPatchTest :
    FunSpec({

        test("ModelPatch sealed roundtrip via JSON keeps polymorphism") {
            val json = Json { classDiscriminator = "kuml_type" }
            val patch: ModelPatch =
                ModelPatch.AddElement(
                    patchId = "P001",
                    appliedAt = "2026-01-01T00:00:00Z",
                    diagramId = "d1",
                    elementKind = "uml.class",
                    elementId = "OrderService",
                    name = "OrderService",
                )
            val encoded = json.encodeToString(ModelPatch.serializer(), patch)
            val decoded = json.decodeFromString(ModelPatch.serializer(), encoded)
            decoded shouldBe patch
        }

        test("ModelPatch.newId returns 26-char ULID-shape strings") {
            val id = ModelPatch.newId()
            id.length shouldBe 26
            // Crockford base32 uses chars 0-9 A-Z (no I L O U)
            id shouldMatch Regex("[0-9A-HJKMNP-TV-Z]{26}")
        }

        test("ModelPatch.nowIso returns ISO-8601 instant") {
            val iso = ModelPatch.nowIso()
            // ISO-8601 contains T and Z
            iso shouldMatch Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*Z")
        }
    })
