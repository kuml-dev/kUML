package dev.kuml.llm.anthropic

import dev.kuml.llm.core.LlmBackend
import dev.kuml.llm.core.LlmException
import dev.kuml.llm.core.LlmMessage
import dev.kuml.llm.core.LlmResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Anthropic Claude API backend.
 *
 * Uses the `POST /v1/messages` endpoint.
 * API reference: https://docs.anthropic.com/en/api/messages
 *
 * @param apiKey Anthropic API key (`ANTHROPIC_API_KEY`).
 * @param modelId Claude model identifier (default: `claude-3-5-haiku-20241022`).
 * @param maxTokens Maximum tokens in the response (default: 2048).
 * @param timeoutSeconds Request timeout in seconds (default: 60).
 */
public class AnthropicBackend(
    private val apiKey: String,
    private val modelId: String = DEFAULT_MODEL,
    private val maxTokens: Int = 2048,
    private val timeoutSeconds: Long = 60L,
) : LlmBackend {
    override val id: String = "anthropic-$modelId"
    override val model: String = modelId

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .build()

    private val json = Json { ignoreUnknownKeys = true }

    override fun complete(
        messages: List<LlmMessage>,
        systemPrompt: String?,
    ): LlmResponse {
        val requestBody = buildRequestBody(messages, systemPrompt)
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(API_URL))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build()

        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                throw LlmException("Network error calling Anthropic API: ${e.message}", e)
            }

        if (response.statusCode() !in 200..299) {
            throw LlmException("Anthropic API error ${response.statusCode()}: ${response.body()}")
        }

        return parseResponse(response.body())
    }

    private fun buildRequestBody(
        messages: List<LlmMessage>,
        systemPrompt: String?,
    ): String {
        val msgArray = messages.map { mapOf("role" to it.role, "content" to it.content) }
        val body =
            buildMap {
                put("model", modelId)
                put("max_tokens", maxTokens)
                if (systemPrompt != null) put("system", systemPrompt)
                put("messages", msgArray)
            }
        return Json.encodeToString(body)
    }

    private fun parseResponse(body: String): LlmResponse {
        val parsed =
            try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                throw LlmException("Failed to parse Anthropic response: ${e.message}", e)
            }
        val text =
            parsed["content"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content
                ?: throw LlmException("No text content in Anthropic response")
        val usage = parsed["usage"]?.jsonObject
        val inputTokens =
            usage
                ?.get("input_tokens")
                ?.jsonPrimitive
                ?.content
                ?.toIntOrNull() ?: 0
        val outputTokens =
            usage
                ?.get("output_tokens")
                ?.jsonPrimitive
                ?.content
                ?.toIntOrNull() ?: 0
        val responseModel = parsed["model"]?.jsonPrimitive?.content ?: modelId
        return LlmResponse(
            content = text,
            model = responseModel,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
        )
    }

    public companion object {
        public const val DEFAULT_MODEL: String = "claude-3-5-haiku-20241022"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
