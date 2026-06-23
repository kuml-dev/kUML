package dev.kuml.ai.tools.patch.store

import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.ai.tools.io.KumlHome
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock

// ── Public domain types ───────────────────────────────────────────────────────

/** Status of a patch row in the persistent store. */
public enum class PatchStatus { PENDING, APPLIED, REJECTED, SUPERSEDED }

/** Result of a [PersistentPatchStore.insert] call. */
public sealed interface InsertResult {
    /** The patch was inserted successfully. */
    public data class Inserted(
        val patchId: String,
    ) : InsertResult

    /**
     * A different patch touched the same element within the 5-second conflict window.
     * The new patch was NOT inserted.
     *
     * @param patchId             The patch that was NOT inserted (the new candidate).
     * @param conflictingPatchId  The existing patch that caused the conflict.
     * @param windowMs            Conflict window size in milliseconds (always 5000).
     */
    public data class ConflictDetected(
        val patchId: String,
        val conflictingPatchId: String,
        val windowMs: Long = CONFLICT_WINDOW_MS,
    ) : InsertResult
}

/** A row returned from [PersistentPatchStore.findBySession]. */
public data class StoredPatch(
    val patchId: String,
    val sessionId: String,
    val ownerId: String,
    val kind: String,
    val elementId: String,
    val payload: String,
    val appliedAt: String,
    val appliedEpochMs: Long,
    val status: PatchStatus,
)

// ── Constants ─────────────────────────────────────────────────────────────────

/** Conflict detection window: 5 seconds in milliseconds. */
public const val CONFLICT_WINDOW_MS: Long = 5_000L

// ── Store ─────────────────────────────────────────────────────────────────────

/**
 * SQLite-backed patch store for AI-editing sessions.
 *
 * One DB file is created per session under `~/.kuml/patches/<sessionId>.db`.
 * The store is `AutoCloseable`; wrap it in `use {}` or close it explicitly.
 *
 * ## Conflict detection
 * [insert] detects when two patches touch the same element within a 5-second window.
 * On conflict, [InsertResult.ConflictDetected] is returned and the patch is NOT
 * inserted — the caller is responsible for emitting a rejection event.
 *
 * ## Thread safety
 * All JDBC calls are blocking. The underlying SQLite connection uses WAL journal mode
 * and a 5-second busy timeout; concurrent access from multiple threads on the same
 * [PersistentPatchStore] instance is safe via the instance-level [lock].
 *
 * ## SQL injection prevention
 * All SQL parameters are bound via [java.sql.PreparedStatement] — no string
 * concatenation is ever used to construct queries.
 *
 * @param conn       Open JDBC connection to the SQLite database.
 * @param sessionId  Session ULID. Used in [findBySession] filtering.
 * @param clock      Clock for `applied_epoch_ms`. Inject a fixed clock in tests.
 */
public class PersistentPatchStore private constructor(
    private val conn: Connection,
    private val sessionId: String,
    private val clock: Clock,
) : AutoCloseable {
    private val lock = Any()
    private val payloadJson = Json { encodeDefaults = false }

    public companion object {
        /**
         * Opens (or creates) the SQLite database for [sessionId] under [dir].
         *
         * Idempotent: subsequent calls with the same sessionId reuse the existing DB.
         */
        public fun open(
            sessionId: String,
            dir: Path = KumlHome.patchesDir(),
            clock: Clock = Clock.systemUTC(),
        ): PersistentPatchStore {
            // Register driver defensively (no-op on modern sqlite-jdbc that auto-registers).
            try {
                Class.forName("org.sqlite.JDBC")
            } catch (_: ClassNotFoundException) {
                // Already registered or on a runtime that doesn't need it.
            }
            val dbPath = dir.resolve("$sessionId.db").toAbsolutePath()
            val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
            conn.autoCommit = true
            // SQLite performance pragmas.
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL;")
                stmt.execute("PRAGMA busy_timeout=5000;")
                stmt.execute("PRAGMA synchronous=NORMAL;")
            }
            createSchema(conn)
            return PersistentPatchStore(conn, sessionId, clock)
        }

        private fun createSchema(conn: Connection) {
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS patches (
                        patch_id         TEXT PRIMARY KEY,
                        session_id       TEXT NOT NULL,
                        owner_id         TEXT NOT NULL,
                        kind             TEXT NOT NULL,
                        element_id       TEXT NOT NULL,
                        payload          TEXT NOT NULL,
                        applied_at       TEXT NOT NULL,
                        applied_epoch_ms INTEGER NOT NULL,
                        status           TEXT NOT NULL CHECK(status IN ('PENDING','APPLIED','REJECTED','SUPERSEDED'))
                    );
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_patches_element_time
                        ON patches(element_id, applied_epoch_ms);
                    """.trimIndent(),
                )
            }
        }
    }

    /**
     * Inserts [patch] with [ownerId] and [status] into the store.
     *
     * Runs a conflict check atomically inside a transaction: if another patch on
     * the same element was inserted within [CONFLICT_WINDOW_MS] ms and is in
     * `PENDING` or `APPLIED` status, returns [InsertResult.ConflictDetected] and
     * does NOT insert.
     *
     * On success returns [InsertResult.Inserted].
     */
    public fun insert(
        patch: ModelPatch,
        ownerId: String,
        status: PatchStatus,
    ): InsertResult {
        val nowMs = clock.millis()
        val nowIso = clock.instant().toString()
        val elementId = touchedElementId(patch) ?: patch.patchId
        val kind = kindOf(patch)
        val payload = payloadJson.encodeToString(ModelPatch.serializer(), patch)

        synchronized(lock) {
            conn.autoCommit = false
            return try {
                // 1. Conflict check: any PENDING/APPLIED patch on same element within window?
                val conflictingId =
                    conn
                        .prepareStatement(
                            """
                            SELECT patch_id FROM patches
                            WHERE element_id = ?
                              AND applied_epoch_ms >= ?
                              AND status IN ('PENDING','APPLIED')
                            ORDER BY applied_epoch_ms DESC
                            LIMIT 1
                            """.trimIndent(),
                        ).use { ps ->
                            ps.setString(1, elementId)
                            ps.setLong(2, nowMs - CONFLICT_WINDOW_MS)
                            ps.executeQuery().use { rs ->
                                if (rs.next()) rs.getString("patch_id") else null
                            }
                        }

                if (conflictingId != null) {
                    conn.rollback()
                    conn.autoCommit = true
                    return InsertResult.ConflictDetected(
                        patchId = patch.patchId,
                        conflictingPatchId = conflictingId,
                    )
                }

                // 2. Insert.
                conn
                    .prepareStatement(
                        """
                        INSERT INTO patches
                            (patch_id, session_id, owner_id, kind, element_id, payload,
                             applied_at, applied_epoch_ms, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, patch.patchId)
                        ps.setString(2, sessionId)
                        ps.setString(3, ownerId)
                        ps.setString(4, kind)
                        ps.setString(5, elementId)
                        ps.setString(6, payload)
                        ps.setString(7, nowIso)
                        ps.setLong(8, nowMs)
                        ps.setString(9, status.name)
                        ps.executeUpdate()
                    }
                conn.commit()
                conn.autoCommit = true
                InsertResult.Inserted(patch.patchId)
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                conn.autoCommit = true
                throw e
            }
        }
    }

    /**
     * Updates the [status] of a patch row identified by [patchId].
     *
     * No-op if [patchId] does not exist in the store.
     */
    public fun updateStatus(
        patchId: String,
        status: PatchStatus,
    ) {
        synchronized(lock) {
            conn
                .prepareStatement(
                    "UPDATE patches SET status = ? WHERE patch_id = ?",
                ).use { ps ->
                    ps.setString(1, status.name)
                    ps.setString(2, patchId)
                    ps.executeUpdate()
                }
        }
    }

    /** Returns all [StoredPatch] rows for [sessionId] ordered by [StoredPatch.appliedEpochMs]. */
    public fun findBySession(sessionId: String): List<StoredPatch> {
        synchronized(lock) {
            return conn
                .prepareStatement(
                    """
                    SELECT patch_id, session_id, owner_id, kind, element_id, payload,
                           applied_at, applied_epoch_ms, status
                    FROM patches
                    WHERE session_id = ?
                    ORDER BY applied_epoch_ms ASC
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, sessionId)
                    ps.executeQuery().use { rs ->
                        val result = mutableListOf<StoredPatch>()
                        while (rs.next()) {
                            result +=
                                StoredPatch(
                                    patchId = rs.getString("patch_id"),
                                    sessionId = rs.getString("session_id"),
                                    ownerId = rs.getString("owner_id"),
                                    kind = rs.getString("kind"),
                                    elementId = rs.getString("element_id"),
                                    payload = rs.getString("payload"),
                                    appliedAt = rs.getString("applied_at"),
                                    appliedEpochMs = rs.getLong("applied_epoch_ms"),
                                    status = PatchStatus.valueOf(rs.getString("status")),
                                )
                        }
                        result
                    }
                }
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { conn.close() }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun touchedElementId(patch: ModelPatch): String? =
        when (patch) {
            is ModelPatch.AddElement -> patch.elementId
            is ModelPatch.RemoveElement -> patch.elementId
            is ModelPatch.UpdateAttribute -> patch.ownerId
            is ModelPatch.RenameElement -> patch.elementId
            is ModelPatch.AddRelationship -> patch.relationshipId
        }

    private fun kindOf(patch: ModelPatch): String =
        when (patch) {
            is ModelPatch.AddElement -> patch.elementKind
            is ModelPatch.RemoveElement -> "remove"
            is ModelPatch.UpdateAttribute -> "update.${patch.field}"
            is ModelPatch.RenameElement -> "rename"
            is ModelPatch.AddRelationship -> patch.relationshipKind
        }
}
