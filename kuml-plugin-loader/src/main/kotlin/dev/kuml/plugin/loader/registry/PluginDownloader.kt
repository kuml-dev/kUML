package dev.kuml.plugin.loader.registry

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Holds a downloaded plugin JAR and (optionally) its detached signature. */
public data class DownloadedPlugin(
    val jar: Path,
    val sig: String?,
)

/**
 * Downloads a plugin JAR (and its `<url>.sig`) from a registry `downloads` URL.
 *
 * ## Security
 * - **SSRF**: only http/https schemes; private/loopback/link-local hosts are rejected
 *   (RFC 1918, loopback 127.x, APIPA 169.254.x, IPv6 ::1).
 * - **DoS**: enforce [maxBytes] response cap and connect/request timeouts.
 * - **Resource leak**: temp files are created in a dedicated temp dir; caller is
 *   responsible for cleanup (files are marked delete-on-exit as a safety net).
 */
public open class PluginDownloader(
    private val timeoutSeconds: Long = 30,
    private val maxBytes: Long = 64L * 1024 * 1024, // 64 MiB cap
) {
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    /**
     * Download a plugin JAR from [downloadsUrl].
     *
     * The `.sig` file is fetched from `"$downloadsUrl.sig"` — if that request fails the
     * signature is silently omitted (unsigned install path; the caller decides whether to
     * enforce signature verification).
     *
     * @throws PluginRegistryException on network failure, disallowed URL scheme, blocked
     *   host (SSRF guard), or response exceeding [maxBytes].
     */
    public open fun download(downloadsUrl: String): DownloadedPlugin {
        val uri = parseAndValidateUrl(downloadsUrl)

        val tempDir = Files.createTempDirectory("kuml-plugin-download-")
        val fileName = uri.path.substringAfterLast('/').ifBlank { "plugin.jar" }
        val jarPath = tempDir.resolve(fileName)
        jarPath.toFile().deleteOnExit()

        val jarBytes = fetchBytes(uri.toString())
        Files.write(jarPath, jarBytes)

        // Try to fetch the detached .sig — graceful if missing
        val sigContent =
            runCatching {
                val sigUrl = "$downloadsUrl.sig"
                parseAndValidateUrl(sigUrl) // validate URL before fetching
                fetchString(sigUrl)
            }.getOrNull()

        return DownloadedPlugin(jar = jarPath, sig = sigContent)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun parseAndValidateUrl(url: String): URI {
        val uri =
            try {
                URI.create(url)
            } catch (e: IllegalArgumentException) {
                throw PluginRegistryException("Invalid download URL: $url", e)
            }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw PluginRegistryException(
                "Download URL must use http or https scheme (got '$scheme'): $url",
            )
        }

        val host = uri.host ?: throw PluginRegistryException("Download URL has no host: $url")
        if (!isHostAllowed(host)) {
            throw PluginRegistryException(
                "Download URL points to a private/loopback host (SSRF guard): $url",
            )
        }

        return uri
    }

    /**
     * Returns `true` if the given [host] is safe to connect to.
     *
     * The default implementation rejects loopback, private, link-local and
     * any-local addresses (SSRF guard). Subclasses may override this method
     * to alter the allowed set — this is intentionally kept `protected` so
     * tests can inject a permissive implementation without bypassing scheme
     * validation.
     */
    protected open fun isHostAllowed(host: String): Boolean = !isPrivateOrLoopback(host)

    private fun isPrivateOrLoopback(host: String): Boolean {
        // Allow the check to fail gracefully (e.g. unresolvable host in tests)
        val addr =
            runCatching { InetAddress.getByName(host) }.getOrNull()
                ?: return false
        return addr.isLoopbackAddress ||
            addr.isSiteLocalAddress ||
            addr.isLinkLocalAddress ||
            addr.isAnyLocalAddress ||
            addr.hostAddress == "::1"
    }

    private fun fetchBytes(url: String): ByteArray {
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build()

        val response =
            try {
                http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: Exception) {
                throw PluginRegistryException("Download failed for $url: ${e.message}", e)
            }

        if (response.statusCode() != 200) {
            response.body().close()
            throw PluginRegistryException(
                "Download returned HTTP ${response.statusCode()} for $url",
            )
        }

        // Stream with a hard cap enforced during download — prevents OOM from chunked or
        // spoofed-Content-Length responses that exceed maxBytes before we see the full body.
        return response.body().use { stream ->
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            var n: Int
            while (stream.read(buf).also { n = it } != -1) {
                total += n
                if (total > maxBytes) {
                    throw PluginRegistryException(
                        "Download exceeds size limit (${maxBytes / 1024 / 1024} MiB): $url",
                    )
                }
                baos.write(buf, 0, n)
            }
            baos.toByteArray()
        }
    }

    private fun fetchString(url: String): String {
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build()

        val response =
            try {
                http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: Exception) {
                throw PluginRegistryException("Download failed for $url: ${e.message}", e)
            }

        if (response.statusCode() != 200) {
            response.body().close()
            throw PluginRegistryException(
                "Download returned HTTP ${response.statusCode()} for $url",
            )
        }

        // Stream with a hard cap enforced during download — prevents OOM from chunked or
        // spoofed-Content-Length responses that exceed maxBytes before we see the full body.
        return response.body().use { stream ->
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            var n: Int
            while (stream.read(buf).also { n = it } != -1) {
                total += n
                if (total > maxBytes) {
                    throw PluginRegistryException(
                        "Download exceeds size limit (${maxBytes / 1024 / 1024} MiB): $url",
                    )
                }
                baos.write(buf, 0, n)
            }
            baos.toString(Charsets.UTF_8)
        }
    }
}
