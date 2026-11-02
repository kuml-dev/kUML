package dev.kuml.desktop.about

import java.awt.Desktop
import java.awt.HeadlessException
import java.io.IOException
import java.net.URI

/** Opens an [AboutLink] in the default browser; injectable so the failure paths are testable. */
internal interface BrowserPort {
    fun isSupported(): Boolean

    /** Throws [IOException], [SecurityException] or [UnsupportedOperationException] on failure. */
    fun browse(link: AboutLink)
}

/** Default [BrowserPort] backed by `java.awt.Desktop`. */
internal object AwtBrowserPort : BrowserPort {
    override fun isSupported(): Boolean =
        try {
            Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
        } catch (_: HeadlessException) {
            false
        }

    override fun browse(link: AboutLink) {
        Desktop.getDesktop().browse(URI.create(link.url))
    }
}

internal enum class BrowseOutcome { OPENED, UNSUPPORTED, FAILED }

/** The single place that translates browser exceptions into a [BrowseOutcome]. */
internal fun BrowserPort.openLink(link: AboutLink): BrowseOutcome {
    if (!isSupported()) return BrowseOutcome.UNSUPPORTED
    return try {
        browse(link)
        BrowseOutcome.OPENED
    } catch (_: IOException) {
        BrowseOutcome.FAILED
    } catch (_: SecurityException) {
        BrowseOutcome.FAILED
    } catch (_: UnsupportedOperationException) {
        BrowseOutcome.FAILED
    }
}
