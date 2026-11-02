package dev.kuml.desktop.about

/** The only addresses the About dialog may open. Fixed at compile time; no user-supplied URLs. */
internal enum class AboutLink(
    val url: String,
) {
    WEBSITE("https://kuml.dev"),
    LICENSE("https://www.apache.org/licenses/LICENSE-2.0"),
}
