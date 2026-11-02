package dev.kuml.desktop.about

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.InputStream

private val logger = KotlinLogging.logger {}

/**
 * Builds the text of the licenses dialog: the bundled NOTICE followed by the font license texts it
 * references (`license text: <path>` lines). Referenced paths are mapped onto the classpath and
 * strictly validated, so a hostile NOTICE cannot read arbitrary resources.
 */
internal class LicenseTextLoader(
    private val classLoader: ClassLoader = LicenseTextLoader::class.java.classLoader,
) {
    /** Returns the text, or null when the NOTICE resource is missing. */
    fun load(): String? {
        val notice = readResource(NOTICE_RESOURCE) ?: return null
        val out = StringBuilder(notice)
        for (resource in licenseResources(notice)) {
            val text = readResource(resource)
            if (text == null) {
                logger.warn { "License resource not found on classpath: $resource" }
                continue
            }
            out
                .append("\n\n--- ")
                .append(resource.substringAfterLast('/'))
                .append(" ---\n")
                .append(text)
        }
        return out.toString()
    }

    private fun licenseResources(notice: String): List<String> =
        notice
            .lineSequence()
            .mapNotNull { LICENSE_LINE.matchEntire(it)?.groupValues?.get(1) }
            .mapNotNull { toClasspathPath(it) }
            .distinct()
            .toList()

    private fun readResource(path: String): String? = classLoader.getResourceAsStream(path)?.use { readCapped(it) }

    private fun readCapped(stream: InputStream): String? {
        val bytes = stream.readNBytes(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) {
            logger.warn { "License resource exceeds $MAX_BYTES bytes; skipped" }
            return null
        }
        return String(bytes, Charsets.UTF_8)
    }

    internal companion object {
        const val NOTICE_RESOURCE = "dev/kuml/desktop/NOTICE.txt"
        const val MAX_BYTES = 256 * 1024
        private val LICENSE_LINE = Regex("""^\s*license text:\s*(\S+)\s*$""")
        private val FILE_NAME = Regex("""^[A-Za-z0-9-]+-OFL\.txt$""")
        private const val MARKER = "src/main/resources/"

        /** Maps a NOTICE path onto a classpath path, or null when it is not an accepted OFL file. */
        fun toClasspathPath(noticePath: String): String? {
            val idx = noticePath.indexOf(MARKER)
            if (idx < 0) return null
            val rest = noticePath.substring(idx + MARKER.length)
            if (rest.isEmpty() || rest.startsWith("/") || "\\" in rest || ".." in rest) return null
            return rest.takeIf { FILE_NAME.matches(it.substringAfterLast('/')) }
        }
    }
}
