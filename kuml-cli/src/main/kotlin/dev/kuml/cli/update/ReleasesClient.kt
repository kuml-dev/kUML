package dev.kuml.cli.update

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Pluggable HTTP gateway for the GitHub Releases API.
 *
 * Modelled as an interface so the tests can swap in an in-memory implementation
 * (`StubReleasesClient`) without spinning up a local HTTP server for every
 * positive-path check. The real implementation ([HttpReleasesClient]) is used
 * for the integration test that runs against an embedded `com.sun.net.httpserver`.
 *
 * Errors are surfaced as a sealed [Result] rather than thrown — `update check`
 * has a dedicated `ONLINE_ERROR` exit code, and a thrown exception in the
 * happy path adds nothing.
 */
internal interface ReleasesClient {
    /** Fetch the single `releases/latest` resource. */
    fun fetchLatest(): Result

    /** Fetch the most recent N releases (including pre-releases) — used by `update notes --target=…`. */
    fun fetchAll(limit: Int = 10): ListResult

    sealed interface Result {
        data class Ok(
            val release: ReleaseInfo,
        ) : Result

        /** A non-2xx response — auth, rate-limit, 404 (no releases yet) etc. */
        data class HttpError(
            val statusCode: Int,
            val body: String,
        ) : Result

        /** Network failure, DNS, timeout, JSON parse error, … */
        data class Failure(
            val message: String,
            val cause: Throwable? = null,
        ) : Result
    }

    sealed interface ListResult {
        data class Ok(
            val releases: List<ReleaseInfo>,
        ) : ListResult

        data class HttpError(
            val statusCode: Int,
            val body: String,
        ) : ListResult

        data class Failure(
            val message: String,
            val cause: Throwable? = null,
        ) : ListResult
    }
}

/**
 * Default implementation backed by the JDK 21 `java.net.http.HttpClient`. No
 * third-party HTTP dependency — important for the GraalVM-native-image story.
 *
 * @param baseUrl Configurable so the integration test can point at
 *   `http://127.0.0.1:<random-port>` instead of `api.github.com`.
 * @param userAgent GitHub *requires* a non-default User-Agent on API requests.
 *   We send the same string we'd send in the version banner — no extra
 *   identifying data, no tracking cookies.
 */
internal class HttpReleasesClient(
    private val baseUrl: String = "https://api.github.com",
    private val repo: String = "kuml-dev/kUML",
    private val userAgent: String = "kuml-cli",
    private val timeout: Duration = Duration.ofSeconds(10),
) : ReleasesClient {
    private val client: HttpClient by lazy {
        HttpClient
            .newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    override fun fetchLatest(): ReleasesClient.Result {
        val url = "$baseUrl/repos/$repo/releases/latest"
        return doRequest(url) { body ->
            ReleasesClient.Result.Ok(ReleaseInfo.JSON.decodeFromString<ReleaseInfo>(body))
        }
    }

    override fun fetchAll(limit: Int): ReleasesClient.ListResult {
        val capped = limit.coerceIn(1, 100) // GitHub max-page-size is 100.
        val url = "$baseUrl/repos/$repo/releases?per_page=$capped"
        return doListRequest(url) { body ->
            ReleasesClient.ListResult.Ok(ReleaseInfo.JSON.decodeFromString<List<ReleaseInfo>>(body))
        }
    }

    private fun doRequest(
        url: String,
        onOk: (String) -> ReleasesClient.Result,
    ): ReleasesClient.Result =
        try {
            val response = sendGet(url)
            if (response.statusCode() in 200..299) {
                onOk(response.body())
            } else {
                ReleasesClient.Result.HttpError(response.statusCode(), response.body())
            }
        } catch (e: Exception) {
            ReleasesClient.Result.Failure(e.message ?: e::class.simpleName ?: "unknown error", e)
        }

    private fun doListRequest(
        url: String,
        onOk: (String) -> ReleasesClient.ListResult,
    ): ReleasesClient.ListResult =
        try {
            val response = sendGet(url)
            if (response.statusCode() in 200..299) {
                onOk(response.body())
            } else {
                ReleasesClient.ListResult.HttpError(response.statusCode(), response.body())
            }
        } catch (e: Exception) {
            ReleasesClient.ListResult.Failure(e.message ?: e::class.simpleName ?: "unknown error", e)
        }

    private fun sendGet(url: String): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", userAgent)
                .GET()
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
