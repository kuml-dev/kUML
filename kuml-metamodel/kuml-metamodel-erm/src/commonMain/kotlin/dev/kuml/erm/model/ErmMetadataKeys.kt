package dev.kuml.erm.model

/**
 * ADR-0016 §2.3 — well-known [ErmEntity.metadata] keys consumed by SQL-DDL-adjacent
 * code generators. Metadata is a generic `Map<String, KumlMetaValue>` on every
 * [ErmElement]; these constants are the shared vocabulary so emitters agree on
 * the same key names without a hard dependency between them.
 *
 * [HYPERTABLE] marks an entity as a TimescaleDB hypertable (Postgres-only,
 * honored by `ErmSqlEmitter`'s `renderHypertables`; Exposed has no matching
 * construct and only emits an explanatory comment). The value is a
 * `KumlMetaValue.Entries` map keyed by [HT_TIME_COLUMN] (required) and
 * [HT_CHUNK_INTERVAL] (optional).
 */
public object ErmMetadataKeys {
    public const val HYPERTABLE: String = "dev.kuml.erm.timescale.hypertable"
    public const val HT_TIME_COLUMN: String = "timeColumn"
    public const val HT_CHUNK_INTERVAL: String = "chunkInterval"

    /**
     * Overrides the mechanically-derived Kotlin `object` name for generated
     * Exposed `Table` objects (ErmExposedEmitter). Value is a single
     * `KumlMetaValue.Text`. When absent, the emitter falls back to
     * PascalCase(entity.name) — unchanged, existing behaviour.
     */
    public const val KOTLIN_OBJECT_NAME: String = "dev.kuml.erm.exposed.kotlinObjectName"
}
