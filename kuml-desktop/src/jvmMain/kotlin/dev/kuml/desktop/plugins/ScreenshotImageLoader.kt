package dev.kuml.desktop.plugins

import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * V3.1.13 — Singleton [ImageLoader] for plugin marketplace screenshots.
 *
 * Features:
 * - Ktor JVM network fetcher (required for remote HTTPS image loading on desktop JVM).
 * - 10 MB LRU disk cache rooted at `<tmpdir>/kuml-screenshots`.
 * - Small in-memory cache cap to avoid unbounded heap growth.
 *
 * Exposed via [screenshotImageLoader] for use in Compose composables.
 */

/** Returns the absolute path of the screenshot disk-cache directory. */
internal fun screenshotCacheDir(): String = "${System.getProperty("java.io.tmpdir")}${File.separator}kuml-screenshots"

private val imageLoaderInstance: ImageLoader by lazy {
    ImageLoader.Builder(
        coil3.PlatformContext.INSTANCE,
    ).components {
        add(
            KtorNetworkFetcherFactory(
                httpClient = {
                    HttpClient(Java) {
                        install(HttpTimeout) {
                            connectTimeoutMillis = 5_000
                            requestTimeoutMillis = 15_000
                            socketTimeoutMillis = 15_000
                        }
                    }
                },
            ),
        )
    }.memoryCache {
        MemoryCache.Builder()
            .maxSizeBytes(4L * 1024 * 1024) // 4 MB in-memory cap
            .build()
    }.diskCache {
        DiskCache.Builder()
            .directory(File(screenshotCacheDir()).toOkioPath())
            .maxSizeBytes(10L * 1024 * 1024) // 10 MB LRU disk cache
            .build()
    }.build()
}

/** Returns the shared [ImageLoader] instance for screenshot thumbnails. */
internal fun screenshotImageLoader(): ImageLoader = imageLoaderInstance
