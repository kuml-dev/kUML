package dev.kuml.runtime.trace.otlp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class OtlpExport(
    val resourceSpans: List<OtlpResourceSpans>,
)

@Serializable
public data class OtlpResourceSpans(
    val resource: OtlpResource,
    val scopeSpans: List<OtlpScopeSpans>,
)

@Serializable
public data class OtlpResource(
    val attributes: List<OtlpKeyValue> = emptyList(),
)

@Serializable
public data class OtlpScopeSpans(
    val scope: OtlpScope,
    val spans: List<OtlpSpan>,
)

@Serializable
public data class OtlpScope(
    val name: String,
    val version: String,
)

@Serializable
public data class OtlpSpan(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String = "",
    val name: String,
    val kind: Int = 1,
    val startTimeUnixNano: String,
    val endTimeUnixNano: String,
    val attributes: List<OtlpKeyValue> = emptyList(),
    val events: List<OtlpEvent> = emptyList(),
    val status: OtlpStatus = OtlpStatus(),
)

@Serializable
public data class OtlpEvent(
    val timeUnixNano: String,
    val name: String,
    val attributes: List<OtlpKeyValue> = emptyList(),
)

@Serializable
public data class OtlpStatus(
    val code: Int = 0,
    val message: String = "",
)

@Serializable
public data class OtlpKeyValue(
    val key: String,
    val value: OtlpAnyValue,
)

@Serializable
public data class OtlpAnyValue(
    @SerialName("stringValue") val stringValue: String? = null,
    @SerialName("boolValue") val boolValue: Boolean? = null,
    @SerialName("intValue") val intValue: String? = null, // OTLP encodes int64 as string
)

public object OtlpAnyValues {
    public fun str(s: String): OtlpAnyValue = OtlpAnyValue(stringValue = s)

    public fun bool(b: Boolean): OtlpAnyValue = OtlpAnyValue(boolValue = b)

    public fun int(i: Long): OtlpAnyValue = OtlpAnyValue(intValue = i.toString())
}

public fun kv(
    key: String,
    value: String,
): OtlpKeyValue = OtlpKeyValue(key, OtlpAnyValues.str(value))

public fun kv(
    key: String,
    value: Boolean,
): OtlpKeyValue = OtlpKeyValue(key, OtlpAnyValues.bool(value))

public fun kv(
    key: String,
    value: Long,
): OtlpKeyValue = OtlpKeyValue(key, OtlpAnyValues.int(value))
