package dev.kuml.core.dsl.layout

/**
 * Minimales Interface für Objekte mit stabiler ID.
 *
 * Wird von [LayoutHintsBuilder]-Methoden akzeptiert, um typsichere Builder-Handle-
 * Overloads zu ermöglichen (z.B. `rightOf(myHandle)`).
 *
 * Da die Metamodell-Datenklassen (UmlClass, C4Container, …) aus ADR-0001-Gründen
 * nicht direkt `HasId` implementieren dürfen, werden für die konkrete Anwendung
 * Convenience-Extensions in `LayoutHintsBuilderExtensions.kt` angeboten,
 * die den `.id`-Wert aus den bekannten Typen extrahieren. Der `HasId`-Overload bleibt
 * für eigene DSL-Erweiterungen offen:
 *
 * ```kotlin
 * val ref = object : HasId { override val id = "my-element-id" }
 * layoutHintsBuilder.rightOf(ref)
 * ```
 */
public interface HasId {
    /** Stabile, eindeutige ID des Elements. */
    public val id: String
}
