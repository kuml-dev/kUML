package dev.kuml.runtime

import java.io.File

/** Lädt eine Event-Liste aus einer JSON-Datei (Schema `kuml.events.v1`). */
public fun loadEvents(file: File): List<Event> {
    val raw = file.readText()
    val ef = KumlRuntimeJson.decodeFromString(EventFile.serializer(), raw)
    require(ef.schema == EventFile.SCHEMA) {
        "Unexpected events schema '${ef.schema}', expected '${EventFile.SCHEMA}'"
    }
    return ef.events
}

/** Schreibt einen Trace als JSON-Datei (Schema `kuml.trace.v1`). */
public fun writeTrace(
    trace: List<TraceEntry>,
    file: File,
    modelId: String? = null,
) {
    val tf = TraceFile(modelId = modelId, entries = trace)
    file.parentFile?.mkdirs()
    file.writeText(KumlRuntimeJson.encodeToString(TraceFile.serializer(), tf))
}

/** Lädt einen Trace aus einer JSON-Datei. */
public fun loadTrace(file: File): TraceFile {
    val raw = file.readText()
    return KumlRuntimeJson.decodeFromString(TraceFile.serializer(), raw)
}
