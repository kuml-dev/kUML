package dev.kuml.desktop.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ConversationMeta(
    val sessionId: String,
    val updatedAt: Long,
    val preview: String?,
)

class ConversationStore(val baseDir: File) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun save(conv: Conversation) {
        baseDir.mkdirs()
        val target = File(baseDir, "${conv.sessionId}.json")
        val tmp = File(baseDir, "${conv.sessionId}.json.tmp")
        tmp.writeText(json.encodeToString(conv))
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun load(sessionId: String): Conversation? {
        val f = File(baseDir, "$sessionId.json")
        return if (f.exists()) {
            runCatching { json.decodeFromString<Conversation>(f.readText()) }.getOrNull()
        } else {
            null
        }
    }

    fun list(): List<ConversationMeta> =
        (baseDir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f -> runCatching { json.decodeFromString<Conversation>(f.readText()) }.getOrNull() }
            .map { c ->
                ConversationMeta(
                    sessionId = c.sessionId,
                    updatedAt = c.updatedAt,
                    preview = c.messages.filterIsInstance<ConversationMessage.User>().firstOrNull()?.text?.take(60),
                )
            }

    companion object {
        fun default(): ConversationStore =
            ConversationStore(File(System.getProperty("user.home"), ".kuml/ai-sessions"))
    }
}
