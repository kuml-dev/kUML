package dev.kuml.ai.tools.patch.store

import dev.kuml.ai.tools.context.ModelPatch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PersistentPatchStoreTest :
    FunSpec({

        // ── Helpers ───────────────────────────────────────────────────────────

        fun tempDir() =
            Files.createTempDirectory("kuml-patch-store-test-").also {
                it.toFile().deleteOnExit()
            }

        fun fixedClock(epochMs: Long): Clock = Clock.fixed(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC)

        fun addPatch(
            elementId: String,
            patchId: String = ModelPatch.newId(),
        ) = ModelPatch.AddElement(
            patchId = patchId,
            appliedAt = ModelPatch.nowIso(),
            diagramId = null,
            elementKind = "uml.class",
            elementId = elementId,
            name = "TestClass",
        )

        // ── Basic CRUD ────────────────────────────────────────────────────────

        test("open creates schema; insert one patch; findBySession returns it with APPLIED status") {
            val dir = tempDir()
            val sessionId = "SES-TEST-001"
            val clock = fixedClock(1_000_000L)

            PersistentPatchStore.open(sessionId, dir, clock).use { store ->
                val patch = addPatch("elem-001")
                val result = store.insert(patch, "alice", PatchStatus.APPLIED)
                result.shouldBeInstanceOf<InsertResult.Inserted>()
                (result as InsertResult.Inserted).patchId shouldBe patch.patchId

                val rows = store.findBySession(sessionId)
                rows shouldHaveSize 1
                rows[0].patchId shouldBe patch.patchId
                rows[0].ownerId shouldBe "alice"
                rows[0].elementId shouldBe "elem-001"
                rows[0].status shouldBe PatchStatus.APPLIED
                rows[0].sessionId shouldBe sessionId
            }
        }

        // ── Conflict window ───────────────────────────────────────────────────

        test("second patch on same element within 5s returns ConflictDetected") {
            val dir = tempDir()
            val sessionId = "SES-CONFLICT-001"
            val t0 = 1_000_000L

            PersistentPatchStore.open(sessionId, dir, fixedClock(t0)).use { storeT0 ->
                val patch1 = addPatch("elem-X")
                storeT0
                    .insert(patch1, "alice", PatchStatus.APPLIED)
                    .shouldBeInstanceOf<InsertResult.Inserted>()
            }

            // Same element, 2 seconds later — within the 5s window
            val t1 = t0 + 2_000L
            PersistentPatchStore.open(sessionId, dir, fixedClock(t1)).use { storeT1 ->
                val patch2 = addPatch("elem-X")
                val result = storeT1.insert(patch2, "alice", PatchStatus.APPLIED)
                result.shouldBeInstanceOf<InsertResult.ConflictDetected>()
                val conflict = result as InsertResult.ConflictDetected
                conflict.patchId shouldBe patch2.patchId
                conflict.windowMs shouldBe CONFLICT_WINDOW_MS
            }
        }

        test("patch on same element after 5s window returns Inserted (no conflict)") {
            val dir = tempDir()
            val sessionId = "SES-NOCONFLICT-001"
            val t0 = 1_000_000L

            PersistentPatchStore.open(sessionId, dir, fixedClock(t0)).use { storeT0 ->
                val patch1 = addPatch("elem-Y")
                storeT0
                    .insert(patch1, "alice", PatchStatus.APPLIED)
                    .shouldBeInstanceOf<InsertResult.Inserted>()
            }

            // 6 seconds later — outside the 5s window
            val t1 = t0 + 6_001L
            PersistentPatchStore.open(sessionId, dir, fixedClock(t1)).use { storeT1 ->
                val patch2 = addPatch("elem-Y")
                val result = storeT1.insert(patch2, "alice", PatchStatus.APPLIED)
                result.shouldBeInstanceOf<InsertResult.Inserted>()
            }
        }

        // ── updateStatus ──────────────────────────────────────────────────────

        test("updateStatus PENDING to APPLIED to SUPERSEDED is reflected on read") {
            val dir = tempDir()
            val sessionId = "SES-STATUS-001"

            PersistentPatchStore.open(sessionId, dir).use { store ->
                val patch = addPatch("elem-status")
                val insertResult = store.insert(patch, "alice", PatchStatus.PENDING)
                insertResult.shouldBeInstanceOf<InsertResult.Inserted>()

                store.updateStatus(patch.patchId, PatchStatus.APPLIED)
                store.findBySession(sessionId)[0].status shouldBe PatchStatus.APPLIED

                store.updateStatus(patch.patchId, PatchStatus.SUPERSEDED)
                store.findBySession(sessionId)[0].status shouldBe PatchStatus.SUPERSEDED
            }
        }

        // ── DB persistence across instances ───────────────────────────────────

        test("data persists across store instances using the same session DB file") {
            val dir = tempDir()
            val sessionId = "SES-PERSIST-001"
            val patchId = ModelPatch.newId()

            // Write in first instance
            PersistentPatchStore.open(sessionId, dir).use { store ->
                val r = store.insert(addPatch("elem-persist", patchId), "alice", PatchStatus.APPLIED)
                r.shouldBeInstanceOf<InsertResult.Inserted>()
            }

            // Read in second instance
            PersistentPatchStore.open(sessionId, dir).use { store ->
                val rows = store.findBySession(sessionId)
                rows shouldHaveSize 1
                rows[0].patchId shouldBe patchId
                rows[0].status shouldBe PatchStatus.APPLIED
            }
        }

        // ── DB file location ──────────────────────────────────────────────────

        test("DB file is created at <dir>/<sessionId>.db") {
            val dir = tempDir()
            val sessionId = "SES-LOC-001"
            PersistentPatchStore.open(sessionId, dir).use { store ->
                store.insert(addPatch("elem-loc"), "alice", PatchStatus.APPLIED)
            }
            Files.exists(dir.resolve("$sessionId.db")) shouldBe true
        }

        // ── SQL injection probe ───────────────────────────────────────────────

        test("patch with SQL injection in patchId is stored verbatim and table survives") {
            val dir = tempDir()
            val sessionId = "SES-INJECT-001"
            val sqlInjectionPatchId = "'; DROP TABLE patches;--"

            PersistentPatchStore.open(sessionId, dir).use { store ->
                // The patchId field goes through a PreparedStatement parameter — never
                // concatenated into SQL. The table must survive this insert.
                val patch =
                    ModelPatch.AddElement(
                        patchId = sqlInjectionPatchId,
                        appliedAt = ModelPatch.nowIso(),
                        diagramId = null,
                        elementKind = "uml.class",
                        elementId = "elem-inject",
                        name = "InjectionTarget",
                    )
                val result = store.insert(patch, "attacker", PatchStatus.APPLIED)
                result.shouldBeInstanceOf<InsertResult.Inserted>()

                // Table must still exist and return the row
                val rows = store.findBySession(sessionId)
                rows shouldHaveSize 1
                rows[0].patchId shouldBe sqlInjectionPatchId
            }
        }

        // ── Different elements have independent conflict windows ───────────────

        test("patches on different elements do not conflict with each other") {
            val dir = tempDir()
            val sessionId = "SES-NOXCONFLICT-001"
            val t0 = 1_000_000L

            PersistentPatchStore.open(sessionId, dir, fixedClock(t0)).use { store ->
                val patchA = addPatch("elem-A")
                val patchB = addPatch("elem-B")
                val rA = store.insert(patchA, "alice", PatchStatus.APPLIED)
                rA.shouldBeInstanceOf<InsertResult.Inserted>()
                // elem-B is a different element — should not conflict
                val rB = store.insert(patchB, "alice", PatchStatus.APPLIED)
                rB.shouldBeInstanceOf<InsertResult.Inserted>()
            }
        }
    })
