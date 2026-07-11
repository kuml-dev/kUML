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
}
