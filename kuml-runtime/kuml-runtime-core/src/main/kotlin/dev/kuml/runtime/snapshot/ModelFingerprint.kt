package dev.kuml.runtime.snapshot

import dev.kuml.runtime.internal.allVertices
import dev.kuml.uml.UmlStateMachine
import java.security.MessageDigest

/**
 * Stabiler SHA-256-Fingerprint für ein UML-State-Machine-Modell.
 * Deterministisch über sortierte Vertex- und Transition-IDs.
 * GraalVM-Native-Image-kompatibel (java.security.MessageDigest ist supportet).
 */
public fun fingerprint(model: UmlStateMachine): String {
    val digest = MessageDigest.getInstance("SHA-256")

    // Alle Vertex-IDs sortiert für Determinismus auf ARM64 vs x86 vs JVM-runs
    val sortedVertexIds = allVertices(model).map { it.id }.sorted()
    for (id in sortedVertexIds) {
        digest.update(id.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }

    // Trennzeichen zwischen Vertex- und Transition-Sektion
    digest.update(1)

    // Alle Transition-IDs sortiert
    val sortedTransitionIds = model.transitions.map { it.id }.sorted()
    for (id in sortedTransitionIds) {
        digest.update(id.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }

    // Hex-Ausgabe der ersten 16 Bytes (32 Zeichen)
    val bytes = digest.digest()
    return bytes.take(16).joinToString("") { "%02x".format(it) }
}

/**
 * Erstellt einen Fingerprint für einen [ActivityRuntimeSpec]-artigen Spec,
 * der durch sortierte Node-IDs und Edge-IDs beschrieben wird.
 */
public fun fingerprintActivity(
    nodeIds: Set<String>,
    edgeIds: Set<String>,
): String {
    val digest = MessageDigest.getInstance("SHA-256")

    val sortedNodeIds = nodeIds.sorted()
    for (id in sortedNodeIds) {
        digest.update(id.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }

    digest.update(1)

    val sortedEdgeIds = edgeIds.sorted()
    for (id in sortedEdgeIds) {
        digest.update(id.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }

    val bytes = digest.digest()
    return bytes.take(16).joinToString("") { "%02x".format(it) }
}
