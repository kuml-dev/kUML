package dev.kuml.lsp

import java.util.concurrent.ConcurrentHashMap

/** Thread-safe in-memory store of open documents keyed by LSP URI. Full-text model. */
class DocumentStore {
    private val docs = ConcurrentHashMap<String, String>()

    fun update(
        uri: String,
        text: String,
    ) {
        docs[uri] = text
    }

    fun text(uri: String): String? = docs[uri]

    fun remove(uri: String) {
        docs.remove(uri)
    }

    fun uris(): Set<String> = docs.keys.toSet()
}
