package dev.kuml.cli.update

/**
 * Minimal SemVer-2.0 parser and comparator for the `kuml update` flow.
 *
 * We only need enough of the spec to compare `BuildConfig.VERSION`
 * against a GitHub release tag (`v0.5.0`, `v0.5.0-rc.1`, etc.):
 *
 *  - the three-number `MAJOR.MINOR.PATCH` core,
 *  - an optional pre-release suffix (`-rc.1`, `-beta.2`, `-SNAPSHOT`),
 *  - ordering: pre-release < release (stable wins ties on the core).
 *
 * Build metadata (`+sha.1234`) is parsed and stored, but per SemVer it
 * does not influence ordering — two versions that differ only by build
 * metadata are considered equal.
 *
 * Strict by design: an unparseable tag throws. The caller decides whether
 * to treat that as an online failure or a soft skip.
 */
internal data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
    val buildMetadata: String? = null,
) : Comparable<SemVer> {
    /** Whether this version carries a pre-release suffix (`-rc.1`, `-SNAPSHOT`, …). */
    val isPreRelease: Boolean get() = preRelease != null

    override fun compareTo(other: SemVer): Int {
        // Core version takes precedence.
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        // Equal cores: a version *without* pre-release is greater than one *with*.
        return when {
            preRelease == null && other.preRelease == null -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> comparePreRelease(preRelease, other.preRelease)
        }
    }

    override fun toString(): String =
        buildString {
            append("$major.$minor.$patch")
            preRelease?.let { append("-").append(it) }
            buildMetadata?.let { append("+").append(it) }
        }

    companion object {
        /**
         * Parse a SemVer string. Accepts an optional leading `v` so GitHub
         * tags (`v0.4.0`) can be passed in verbatim.
         *
         * Returns `null` for unparseable input — callers that want a hard
         * failure should throw at the call site.
         */
        fun parseOrNull(raw: String): SemVer? {
            val trimmed = raw.trim().removePrefix("v").removePrefix("V")
            val regex = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.\-]+))?(?:\+([0-9A-Za-z.\-]+))?$""")
            val match = regex.matchEntire(trimmed) ?: return null
            val (major, minor, patch, preRelease, buildMetadata) = match.destructured
            return SemVer(
                major = major.toInt(),
                minor = minor.toInt(),
                patch = patch.toInt(),
                preRelease = preRelease.ifEmpty { null },
                buildMetadata = buildMetadata.ifEmpty { null },
            )
        }

        /**
         * Compare two pre-release identifier strings according to SemVer rules:
         * numeric identifiers compare numerically and rank lower than
         * alphanumeric ones; identifiers are compared dot-segment by dot-segment.
         */
        private fun comparePreRelease(
            a: String,
            b: String,
        ): Int {
            val aParts = a.split(".")
            val bParts = b.split(".")
            val shared = minOf(aParts.size, bParts.size)
            for (i in 0 until shared) {
                val ai = aParts[i]
                val bi = bParts[i]
                val aNum = ai.toIntOrNull()
                val bNum = bi.toIntOrNull()
                val cmp =
                    when {
                        aNum != null && bNum != null -> aNum.compareTo(bNum)
                        aNum != null -> -1
                        bNum != null -> 1
                        else -> ai.compareTo(bi)
                    }
                if (cmp != 0) return cmp
            }
            // All shared parts equal: the longer pre-release wins (it's "later" in SemVer).
            return aParts.size.compareTo(bParts.size)
        }
    }
}
