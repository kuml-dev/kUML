package dev.kuml.erm.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Logical, dialect-neutral column data type.
 *
 * Sealed so that a later SQL-dialect code generator (V3.4.7) can map every
 * variant exhaustively. [Custom] is the escape hatch for dialect-specific
 * types that have no logical equivalent here (e.g. Postgres `tsvector`).
 *
 * Every variant carries an explicit [SerialName] because [ErmDataType] is
 * serialized as a polymorphic sealed type with `classDiscriminator = "@type"`
 * in the IPC codec (`ExtractedDiagramCodec`) — without a stable discriminator,
 * renaming a class would silently break the wire format.
 *
 * V3.4.1
 */
@Serializable
sealed interface ErmDataType {
    /** Human-readable short form for labels/renderer, e.g. `"VARCHAR(255)"`. */
    fun render(): String

    /** Whole-number type. [bits] is one of 16 (SMALLINT), 32 (INT), 64 (BIGINT). */
    @Serializable
    @SerialName("integer")
    data class Integer(
        val bits: Int = 32,
    ) : ErmDataType {
        override fun render(): String =
            when (bits) {
                16 -> "SMALLINT"
                64 -> "BIGINT"
                else -> "INT"
            }
    }

    /** Fixed-point decimal with [precision] total digits and [scale] fraction digits. */
    @Serializable
    @SerialName("decimal")
    data class Decimal(
        val precision: Int,
        val scale: Int,
    ) : ErmDataType {
        override fun render(): String = "DECIMAL($precision,$scale)"
    }

    /** Floating-point type. [double] selects DOUBLE vs. FLOAT precision. */
    @Serializable
    @SerialName("real")
    data class Real(
        val double: kotlin.Boolean = true,
    ) : ErmDataType {
        override fun render(): String = if (double) "DOUBLE" else "FLOAT"
    }

    /** Variable-length character type with a maximum [length]. */
    @Serializable
    @SerialName("varchar")
    data class Varchar(
        val length: Int = 255,
    ) : ErmDataType {
        override fun render(): String = "VARCHAR($length)"
    }

    /**
     * Enumeration with a fixed literal set. Physically stored as `VARCHAR` +
     * `CHECK (col IN (...))` on every SQL dialect (V3.4.7 deliberate decision —
     * no native Postgres `CREATE TYPE ... AS ENUM`, see CHANGELOG). [name] is the
     * Kotlin-facing type name used by the Exposed emitter to generate a matching
     * `enum class` and reference it via `enumerationByName<T>(...)`.
     */
    @Serializable
    @SerialName("enum")
    data class Enum(
        val name: String,
        val values: List<String>,
    ) : ErmDataType {
        /** Longest literal, floor 1 — used as the physical VARCHAR/varchar() length. */
        val length: Int get() = values.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1

        override fun render(): String = "ENUM($name)"
    }

    /** Unbounded text type. */
    @Serializable
    @SerialName("text")
    data object Text : ErmDataType {
        override fun render(): String = "TEXT"
    }

    /** Boolean type. */
    @Serializable
    @SerialName("boolean")
    data object Boolean : ErmDataType {
        override fun render(): String = "BOOLEAN"
    }

    /** Calendar date (no time-of-day component). */
    @Serializable
    @SerialName("date")
    data object Date : ErmDataType {
        override fun render(): String = "DATE"
    }

    /** Time-of-day (no date component). */
    @Serializable
    @SerialName("time")
    data object Time : ErmDataType {
        override fun render(): String = "TIME"
    }

    /** Date + time, optionally [withTimeZone]. */
    @Serializable
    @SerialName("timestamp")
    data class Timestamp(
        val withTimeZone: kotlin.Boolean = false,
    ) : ErmDataType {
        override fun render(): String = if (withTimeZone) "TIMESTAMPTZ" else "TIMESTAMP"
    }

    /** Universally unique identifier. */
    @Serializable
    @SerialName("uuid")
    data object Uuid : ErmDataType {
        override fun render(): String = "UUID"
    }

    /** Binary large object. */
    @Serializable
    @SerialName("blob")
    data object Blob : ErmDataType {
        override fun render(): String = "BLOB"
    }

    /** Structured JSON document. */
    @Serializable
    @SerialName("json")
    data object Json : ErmDataType {
        override fun render(): String = "JSON"
    }

    /** Dialect-specific escape hatch — [raw] is rendered verbatim. */
    @Serializable
    @SerialName("custom")
    data class Custom(
        val raw: String,
    ) : ErmDataType {
        override fun render(): String = raw
    }
}
