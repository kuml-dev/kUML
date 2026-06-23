package dev.kuml.plugin.loader.registry

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.InetSocketAddress

/**
 * Unit tests for [PluginDownloader] covering:
 *   1. http/https scheme enforcement — file:// and ftp:// must throw.
 *   2. Private/loopback host rejection (127.0.0.1, 192.168.x.x, ::1).
 *   3. Unresolvable hostname passes the SSRF guard (intentional — see below).
 *   4. A mock HTTP server returning a body larger than maxBytes verifies rejection.
 *   5. Successful download returns [DownloadedPlugin] with non-null jar and null
 *      sig when no .sig endpoint exists.
 *
 * Tests that exercise real HTTP (cases 4 and 5) use [LoopbackPermissiveDownloader]
 * which overrides [PluginDownloader.isHostAllowed] to allow loopback addresses,
 * enabling the JDK built-in [HttpServer] on 127.0.0.1.
 */
class PluginDownloaderTest :
    FunSpec({

        // ── 1. Scheme enforcement ─────────────────────────────────────────────

        test("file:// scheme is rejected with PluginRegistryException") {
            val downloader = PluginDownloader()
            val ex =
                shouldThrow<PluginRegistryException> {
                    downloader.download("file:///etc/passwd")
                }
            ex.message shouldContain "http or https"
        }

        test("ftp:// scheme is rejected with PluginRegistryException") {
            val downloader = PluginDownloader()
            val ex =
                shouldThrow<PluginRegistryException> {
                    downloader.download("ftp://plugins.example.com/plugin.jar")
                }
            ex.message shouldContain "http or https"
        }

        // ── 2. Private/loopback host rejection ────────────────────────────────

        test("loopback 127.0.0.1 is rejected by SSRF guard") {
            val downloader = PluginDownloader()
            val ex =
                shouldThrow<PluginRegistryException> {
                    downloader.download("http://127.0.0.1:9999/plugin.jar")
                }
            ex.message shouldContain "private/loopback"
        }

        test("private range 192.168.1.1 is rejected by SSRF guard") {
            val downloader = PluginDownloader()
            val ex =
                shouldThrow<PluginRegistryException> {
                    downloader.download("http://192.168.1.1/plugin.jar")
                }
            ex.message shouldContain "private/loopback"
        }

        test("private range 10.0.0.1 is rejected by SSRF guard") {
            val downloader = PluginDownloader()
            val ex =
                shouldThrow<PluginRegistryException> {
                    downloader.download("http://10.0.0.1/plugin.jar")
                }
            ex.message shouldContain "private/loopback"
        }

        test("IPv6 loopback ::1 is rejected by SSRF guard") {
            val downloader = PluginDownloader()
            val ex =
                shouldThrow<PluginRegistryException> {
                    downloader.download("http://[::1]/plugin.jar")
                }
            ex.message shouldContain "private/loopback"
        }

        // ── 3. Unresolvable hostname passes (intentional) ─────────────────────
        //
        // When DNS resolution fails, isPrivateOrLoopback() cannot confirm the host
        // is private, so isHostAllowed() returns true and the download is attempted.
        // This is intentional: blocking unresolvable hostnames would create a DoS
        // vector where transient DNS failures block legitimate plugin downloads.
        // The actual network attempt fails at connect time and throws
        // PluginRegistryException with a "Download failed" message — NOT a
        // "private/loopback" message.
        //
        test("unresolvable hostname passes SSRF guard and fails at connect, not at host-check") {
            val downloader = PluginDownloader(timeoutSeconds = 2)
            val ex =
                shouldThrow<PluginRegistryException> {
                    downloader.download("http://this-host-does-not-exist-kuml.invalid/plugin.jar")
                }
            // The error must not be about the SSRF guard
            ex.message shouldContain "Download failed"
        }

        // ── 4. DoS: maxBytes cap ──────────────────────────────────────────────

        test("response body exceeding maxBytes throws PluginRegistryException") {
            val bodySize = 1024 * 1024 // 1 MiB actual body
            val maxBytes = 512 * 1024L // 512 KiB cap — body exceeds this

            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/plugin.jar") { exchange ->
                val body = ByteArray(bodySize) { 0x42 }
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            val port = server.address.port

            try {
                val downloader = LoopbackPermissiveDownloader(maxBytes = maxBytes)
                val ex =
                    shouldThrow<PluginRegistryException> {
                        downloader.download("http://127.0.0.1:$port/plugin.jar")
                    }
                ex.message shouldContain "size limit"
            } finally {
                server.stop(0)
            }
        }

        // ── 5. Happy path: no .sig endpoint → null sig ────────────────────────

        test("successful download returns DownloadedPlugin with non-null jar and null sig when no sig exists") {
            val jarContent = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // PK magic bytes

            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            // Serve the JAR and explicitly 404 the .sig so the HttpServer prefix
            // matcher does not accidentally serve the .sig via the root JAR handler.
            server.createContext("/nosig.jar") { exchange ->
                if (exchange.requestURI.path.endsWith(".sig")) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    exchange.sendResponseHeaders(200, jarContent.size.toLong())
                    exchange.responseBody.use { it.write(jarContent) }
                }
            }
            server.start()
            val port = server.address.port

            try {
                val downloader = LoopbackPermissiveDownloader()
                val result = downloader.download("http://127.0.0.1:$port/nosig.jar")

                result.jar.shouldNotBeNull()
                result.jar.toFile().exists() shouldBe true
                result.jar.toFile().readBytes() shouldBe jarContent
                // .sig returned 404 → sig is null (graceful omission)
                result.sig.shouldBeNull()
            } finally {
                server.stop(0)
            }
        }

        // ── Bonus: happy path with .sig present ───────────────────────────────

        test("successful download returns non-null sig when sig endpoint exists") {
            val jarContent = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
            val sigContent = "BASE64SIGCONTENT=="

            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/withsig.jar") { exchange ->
                exchange.sendResponseHeaders(200, jarContent.size.toLong())
                exchange.responseBody.use { it.write(jarContent) }
            }
            server.createContext("/withsig.jar.sig") { exchange ->
                val sigBytes = sigContent.toByteArray()
                exchange.sendResponseHeaders(200, sigBytes.size.toLong())
                exchange.responseBody.use { it.write(sigBytes) }
            }
            server.start()
            val port = server.address.port

            try {
                val downloader = LoopbackPermissiveDownloader()
                val result = downloader.download("http://127.0.0.1:$port/withsig.jar")

                result.jar.shouldNotBeNull()
                result.sig.shouldNotBeNull()
                result.sig shouldBe sigContent
            } finally {
                server.stop(0)
            }
        }
    })

/**
 * Test-only subclass of [PluginDownloader] that allows loopback addresses.
 *
 * Overrides [isHostAllowed] to return `true` for all hosts, enabling
 * tests to use a real [HttpServer] bound to 127.0.0.1 without triggering
 * the SSRF guard. **Never use this class in production code.**
 */
private class LoopbackPermissiveDownloader(
    maxBytes: Long = 64L * 1024 * 1024,
) : PluginDownloader(timeoutSeconds = 10, maxBytes = maxBytes) {
    override fun isHostAllowed(host: String): Boolean = true
}
