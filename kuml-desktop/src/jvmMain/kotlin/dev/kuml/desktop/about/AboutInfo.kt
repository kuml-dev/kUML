package dev.kuml.desktop.about

import dev.kuml.desktop.i18n.Strings

internal data class AboutRow(
    val label: String,
    val value: String,
)

internal object AboutInfo {
    const val APACHE_LICENSE_NAME: String = "Apache License 2.0"

    /** Rows in display order: version, build date, Java. A missing value shows `aboutUnknown`. */
    fun rows(
        strings: Strings,
        version: String,
        buildDate: String?,
        javaVersion: String?,
    ): List<AboutRow> =
        listOf(
            AboutRow(label = strings.about.version, value = version.orUnknown(strings)),
            AboutRow(label = strings.about.buildDate, value = buildDate.orUnknown(strings)),
            AboutRow(label = strings.about.java, value = javaVersion.orUnknown(strings)),
        )

    /** Exactly what the dialog shows, one `Label: value` line per row. */
    fun clipboardText(rows: List<AboutRow>): String = rows.joinToString("\n") { "${it.label}: ${it.value}" }

    fun javaDescription(
        version: String?,
        vendor: String?,
    ): String? {
        val v = version?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        val vend = vendor?.trim().takeUnless { it.isNullOrEmpty() }
        return if (vend == null) v else "$v ($vend)"
    }

    fun systemJavaDescription(): String? =
        javaDescription(version = System.getProperty("java.version"), vendor = System.getProperty("java.vendor"))

    private fun String?.orUnknown(strings: Strings): String = this?.takeIf { it.isNotBlank() } ?: strings.about.unknown
}
