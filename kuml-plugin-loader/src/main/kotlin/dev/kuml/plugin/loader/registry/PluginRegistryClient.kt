package dev.kuml.plugin.loader.registry

import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Fetches the plugin registry index from `plugins.kuml.dev`.
 *
 * Thread-safe. The underlying [HttpClient] is shared and reused.
 */
public class PluginRegistryClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val timeoutSeconds: Long = 10,
) {
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetch and parse the registry index.
     *
     * @throws PluginRegistryException if the network request fails or the response is invalid
     */
    public fun fetchIndex(): PluginRegistryIndex {
        val url = "$baseUrl/plugins/index.json"
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build()

        val response =
            try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                throw PluginRegistryException("Failed to reach registry at $url: ${e.message}", e)
            }

        if (response.statusCode() != 200) {
            throw PluginRegistryException(
                "Registry returned HTTP ${response.statusCode()} for $url",
            )
        }

        return try {
            json.decodeFromString<PluginRegistryIndex>(response.body())
        } catch (e: Exception) {
            throw PluginRegistryException("Failed to parse registry index: ${e.message}", e)
        }
    }

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://plugins.kuml.dev"
    }
}

/** Thrown when the registry cannot be reached or its response is invalid. */
public class PluginRegistryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
