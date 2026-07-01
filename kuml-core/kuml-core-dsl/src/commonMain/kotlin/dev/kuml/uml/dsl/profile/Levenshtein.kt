package dev.kuml.uml.dsl.profile

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
