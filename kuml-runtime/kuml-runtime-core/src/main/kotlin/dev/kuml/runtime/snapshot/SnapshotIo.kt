package dev.kuml.runtime.snapshot

import dev.kuml.runtime.KumlRuntimeJson
import java.io.File

// Lese- und Schreib-Hilfsfunktionen für StateMachineSnapshot und ActivityInstanceSnapshot.
// Alle Funktionen nutzen KumlRuntimeJson (prettyPrint, classDiscriminator="type",
// ignoreUnknownKeys=true) für ein stabiles, versioniertes Format.

/** Schreibt einen [StateMachineSnapshot] als pretty-printed JSON in [file]. */
public fun writeStateMachineSnapshot(
    snapshot: StateMachineSnapshot,
    file: File,
) {
    file.writeText(KumlRuntimeJson.encodeToString(StateMachineSnapshot.serializer(), snapshot))
}

/** Liest einen [StateMachineSnapshot] aus [file]. */
public fun readStateMachineSnapshot(file: File): StateMachineSnapshot =
    KumlRuntimeJson.decodeFromString(StateMachineSnapshot.serializer(), file.readText())

/** Schreibt einen [ActivityInstanceSnapshot] als pretty-printed JSON in [file]. */
public fun writeActivityInstanceSnapshot(
    snapshot: ActivityInstanceSnapshot,
    file: File,
) {
    file.writeText(KumlRuntimeJson.encodeToString(ActivityInstanceSnapshot.serializer(), snapshot))
}

/** Liest einen [ActivityInstanceSnapshot] aus [file]. */
public fun readActivityInstanceSnapshot(file: File): ActivityInstanceSnapshot =
    KumlRuntimeJson.decodeFromString(ActivityInstanceSnapshot.serializer(), file.readText())
