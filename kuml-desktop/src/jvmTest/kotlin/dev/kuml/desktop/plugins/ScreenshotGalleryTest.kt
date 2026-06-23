package dev.kuml.desktop.plugins

import dev.kuml.plugin.loader.registry.PluginRegistryEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.InetAddress

/**
 * V3.1.13 — Headless unit tests for the screenshot gallery pure logic.
 *
 * No Compose test harness required — only the internal pure functions
 * [galleryThumbnails], [screenshotAbsoluteUrl], and [screenshotCacheDir]
 * are verified here, following the convention established in
 * [PatchPreviewDialogTest] and [PluginManagerPaneTest].
 */
class ScreenshotGalleryTest :
    FunSpec({

        // Deterministic stand-in for DNS: resolves any host to a fixed public,
        // routable address (documented example IP 93.184.216.34). Lets the
        // "public host is allowed" assertions run without live DNS, which is
        // unavailable in sandboxed CI environments.
        val publicHostResolver: (String) -> InetAddress =
            { InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte())) }

        // ── galleryThumbnails() ────────────────────────────────────────────────

        test("galleryThumbnails: entry with 0 URLs returns empty list") {
            val entry = makeGalleryEntry(screenshotUrls = emptyList())
            galleryThumbnails(entry).shouldBeEmpty()
        }

        test("galleryThumbnails: entry with 1 URL returns list of size 1") {
            val entry = makeGalleryEntry(screenshotUrls = listOf("screenshots/x/1.png"))
            galleryThumbnails(entry) shouldHaveSize 1
        }

        test("galleryThumbnails: entry with 3 URLs returns list of size 3") {
            val entry =
                makeGalleryEntry(
                    screenshotUrls =
                        listOf(
                            "screenshots/x/1.png",
                            "screenshots/x/2.png",
                            "screenshots/x/3.png",
                        ),
                )
            galleryThumbnails(entry) shouldHaveSize 3
        }

        test("galleryThumbnails: entry with 5 URLs is capped at MAX_GALLERY_THUMBS (3)") {
            val entry =
                makeGalleryEntry(
                    screenshotUrls =
                        listOf(
                            "screenshots/x/1.png",
                            "screenshots/x/2.png",
                            "screenshots/x/3.png",
                            "screenshots/x/4.png",
                            "screenshots/x/5.png",
                        ),
                )
            galleryThumbnails(entry) shouldHaveSize MAX_GALLERY_THUMBS
        }

        test("MAX_GALLERY_THUMBS is 3") {
            MAX_GALLERY_THUMBS shouldBe 3
        }

        // ── screenshotAbsoluteUrl() ────────────────────────────────────────────

        test("screenshotAbsoluteUrl: relative path without leading slash is prefixed with base URL") {
            screenshotAbsoluteUrl("screenshots/x/1.png").shouldNotBeNull() shouldBe
                "https://plugins.kuml.dev/screenshots/x/1.png"
        }

        test("screenshotAbsoluteUrl: relative path with leading slash normalizes correctly") {
            screenshotAbsoluteUrl("/screenshots/x/1.png").shouldNotBeNull() shouldBe
                "https://plugins.kuml.dev/screenshots/x/1.png"
        }

        test("screenshotAbsoluteUrl: absolute https URL to public host is returned") {
            val url = "https://cdn.example.com/screenshot.png"
            screenshotAbsoluteUrl(url, publicHostResolver).shouldNotBeNull()
        }

        test("screenshotAbsoluteUrl: absolute http URL pointing to localhost is blocked (returns null)") {
            screenshotAbsoluteUrl("http://localhost/screenshot.png").shouldBeNull()
        }

        test("screenshotAbsoluteUrl: valid external https URL is returned unchanged") {
            screenshotAbsoluteUrl("https://cdn.example.com/screenshot.png", publicHostResolver).shouldNotBeNull()
        }

        test("SCREENSHOT_BASE_URL is https://plugins.kuml.dev") {
            SCREENSHOT_BASE_URL shouldBe "https://plugins.kuml.dev"
        }

        // ── validateScreenshotUrl() ────────────────────────────────────────────

        test("validateScreenshotUrl: valid external https URL is allowed") {
            validateScreenshotUrl("https://cdn.example.com/screenshot.png", publicHostResolver) shouldBe true
        }

        test("validateScreenshotUrl: localhost is blocked") {
            validateScreenshotUrl("http://localhost/admin") shouldBe false
        }

        test("validateScreenshotUrl: 127.0.0.1 loopback is blocked") {
            validateScreenshotUrl("http://127.0.0.1/secret") shouldBe false
        }

        test("validateScreenshotUrl: RFC1918 10.x address is blocked") {
            validateScreenshotUrl("http://10.0.0.1/admin") shouldBe false
        }

        test("validateScreenshotUrl: RFC1918 192.168.x address is blocked") {
            validateScreenshotUrl("http://192.168.1.1/admin") shouldBe false
        }

        test("validateScreenshotUrl: RFC1918 172.16.x address is blocked") {
            validateScreenshotUrl("http://172.16.0.1/admin") shouldBe false
        }

        test("validateScreenshotUrl: link-local 169.254.x address is blocked") {
            validateScreenshotUrl("http://169.254.1.1/metadata") shouldBe false
        }

        test("validateScreenshotUrl: non-http scheme is blocked") {
            validateScreenshotUrl("ftp://cdn.example.com/file.png") shouldBe false
        }

        test("validateScreenshotUrl: file scheme is blocked") {
            validateScreenshotUrl("file:///etc/passwd") shouldBe false
        }

        // ── screenshotCacheDir() ───────────────────────────────────────────────

        test("screenshotCacheDir resolves under java.io.tmpdir/kuml-screenshots") {
            val dir = screenshotCacheDir()
            dir shouldContain "kuml-screenshots"
            dir shouldContain System.getProperty("java.io.tmpdir")
        }
    })

// ── Test helper ───────────────────────────────────────────────────────────────

private fun makeGalleryEntry(screenshotUrls: List<String>) =
    PluginRegistryEntry(
        id = "dev.kuml.plugin.test",
        category = "theme",
        name = "Test Plugin",
        version = "1.0.0",
        manifest = "plugins/dev.kuml.plugin.test/kuml-plugin.json",
        downloads = "plugins/dev.kuml.plugin.test/releases/",
        screenshotUrls = screenshotUrls,
    )
