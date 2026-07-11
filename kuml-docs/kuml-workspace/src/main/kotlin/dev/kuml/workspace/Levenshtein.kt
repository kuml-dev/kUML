package dev.kuml.workspace

/**
 * Tiny hand-rolled Levenshtein distance, used only for the `type:` did-you-mean
 * suggestion in [OkfValidator].
 *
 * A reusable, more feature-complete implementation already exists at
 * `kuml-core-dsl`'s `dev.kuml.uml.dsl.profile.Levenshtein` (backs
 * `StereotypeValidator`'s did-you-mean), but that object is `internal` to a
 * Kotlin-Multiplatform module — pulling in `kuml-core-dsl` here just for ~20 lines
 * of edit-distance code would be a heavier dependency than the need justifies.
 * This copy is intentionally the same shape and stays private to this module.
 */
internal object Levenshtein {
    fun closest(
        target: String,
        candidates: Collection<String>,
        maxDistance: Int = 3,
    ): String? =
        candidates
            .map { it to distance(target, it) }
            .filter { (_, d) -> d <= maxDistance }
            .minByOrNull { (_, d) -> d }
            ?.first

    private fun distance(
        a: String,
        b: String,
    ): Int {
        val m = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) m[i][0] = i
        for (j in 0..b.length) m[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                m[i][j] =
                    minOf(
                        m[i - 1][j] + 1,
                        m[i][j - 1] + 1,
                        m[i - 1][j - 1] + cost,
                    )
            }
        }
        return m[a.length][b.length]
    }
}
