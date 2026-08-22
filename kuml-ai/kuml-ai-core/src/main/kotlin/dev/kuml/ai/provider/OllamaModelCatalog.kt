package dev.kuml.ai.provider

import kotlinx.coroutines.withTimeoutOrNull
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions

private const val OLLAMA_CLIENT_FQCN = "ai.koog.prompt.executor.ollama.client.OllamaClient"
private const val HTTP_CLIENT_FACTORY_FQCN = "ai.koog.http.client.KoogHttpClient\$Factory"

/**
 * Result of asking Ollama's real `/api/tags` endpoint (via `OllamaClient.getModels()`) for the
 * model ids currently pulled on the user's machine. See [BuiltInProviders.fetchOllamaModelIds].
 */
public sealed class OllamaModelListResult {
    public data class Success(
        val modelIds: List<String>,
    ) : OllamaModelListResult()

    public data class Failure(
        val reason: String,
    ) : OllamaModelListResult()
}

/**
 * Fragt Ollamas echten `/api/tags`-Endpunkt ab (`OllamaClient.getModels()`, intern
 * `DEFAULT_LIST_MODELS_PATH`), REFLEKTIV — `ai.koog:prompt-executor-ollama-client` bleibt
 * `runtimeOnly` (Tree-Shaking-Konvention V3.1.15, siehe [BuiltInProviders]' Klassen-KDoc).
 * `getModels()` ist zudem eine `suspend fun` — ein einfaches `java.lang.reflect.Method.invoke`
 * funktioniert dafür NICHT korrekt (Kotlins Continuation-Parameter/COROUTINE_SUSPENDED-Sentinel
 * würde dabei nicht bedient) — `kotlin-reflect`s [kotlin.reflect.full.callSuspend] ist der dafür
 * vorgesehene, unterstützte Weg; `kotlin-reflect` ist bereits eine `implementation`-Dependency
 * von `kuml-ai-core` (siehe `build.gradle.kts`).
 *
 * **Security (SSRF/DoS):** keine URL-Override-Fläche — die Basis-URL kommt ausschließlich aus
 * `OllamaClient`s eigenem Default (`http://localhost:11434`), es gibt keinen
 * Nutzereingabe-Pfad hierher (konsistent mit `BuiltInProviders.reflectiveGonkaClient`s "keine
 * baseUrl-Override-Fläche"-Kommentar — schließt Base-URL-Injection/SSRF aus). [timeoutMs]
 * deckelt die Wartezeit, damit ein hängender oder nicht erreichbarer lokaler Ollama-Prozess das
 * Modell-Dropdown nicht unbegrenzt blockiert.
 *
 * **Resource-Leak:** der reflektiv konstruierte `OllamaClient` wird nach dem Aufruf explizit
 * geschlossen — `LLMClientAPI` (die von `OllamaClient` implementierte Basis-API) erweitert
 * `java.lang.AutoCloseable` (per Bytecode-Inspektion verifiziert, `ai.koog:prompt-executor-
 * clients-jvm:1.0.0`), ohne das würde jedes Öffnen des Modell-Dropdowns einen neuen,
 * zugrundeliegenden Ktor-HTTP-Client leaken. Der Cast auf [AutoCloseable] braucht KEINE
 * Reflection — `java.lang.AutoCloseable` ist immer auf dem JDK-Klassenpfad, nur `OllamaClient`
 * selbst ist es nicht (daher bleibt `client`s statischer Typ hier `Any`).
 *
 * @param timeoutMs Bounds the whole call (client construction + HTTP round trip). Default 5s.
 */
public suspend fun BuiltInProviders.fetchOllamaModelIds(timeoutMs: Long = 5_000): OllamaModelListResult =
    withTimeoutOrNull(timeoutMs) {
        runCatching {
            val factory = resolveHttpClientFactory()
            val factoryClass = Class.forName(HTTP_CLIENT_FACTORY_FQCN)
            val cls = Class.forName(OLLAMA_CLIENT_FQCN)
            val client = cls.getConstructor(factoryClass).newInstance(factory)
            try {
                val getModelsFn =
                    client::class.declaredMemberFunctions.firstOrNull { it.name == "getModels" }
                        ?: error("OllamaClient has no 'getModels' member function (unexpected koog version?)")

                @Suppress("UNCHECKED_CAST")
                val cards = getModelsFn.callSuspend(client) as List<Any>
                cards.map { card -> card::class.java.getMethod("getName").invoke(card) as String }
            } finally {
                (client as? AutoCloseable)?.close()
            }
        }.fold(
            onSuccess = { OllamaModelListResult.Success(it) },
            onFailure = { OllamaModelListResult.Failure(it.message ?: it.javaClass.simpleName) },
        )
    } ?: OllamaModelListResult.Failure("Ollama /api/tags antwortete nicht innerhalb von ${timeoutMs}ms")
