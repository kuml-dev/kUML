package dev.kuml.runtime.sandbox

/**
 * Registry of built-in functions available in sandboxed action bodies.
 *
 * Functions are identified by their dotted name (e.g. `"log.info"`).
 * Each [Impl] receives the already-evaluated argument list as `List<Any?>`.
 *
 * V2.0.40 — Sandbox-Garantien.
 */
public object BuiltInFunctions {
    /** A built-in function implementation. */
    public fun interface Impl {
        public fun call(args: List<Any?>): Any?
    }

    private val registry: Map<String, Impl> =
        buildMap {
            // ── log ───────────────────────────────────────────────────────────
            // log.* functions return null; side effect appended to __log__ variable.
            // Actual appending is done by EffectExecutor which has access to the instance.
            // Here we just return the formatted message so EffectExecutor can capture it.
            put("log.info") { args -> args.joinToString(" ") { it?.toString() ?: "null" } }
            put("log.warn") { args -> args.joinToString(" ") { it?.toString() ?: "null" } }
            put("log.error") { args -> args.joinToString(" ") { it?.toString() ?: "null" } }
            put("log.debug") { args -> args.joinToString(" ") { it?.toString() ?: "null" } }

            // ── math ──────────────────────────────────────────────────────────
            put("math.min") { args ->
                val av = args.getOrNull(0)
                val bv = args.getOrNull(1)
                if (av is Long && bv is Long) {
                    minOf(av, bv)
                } else {
                    minOf(av.toDouble(), bv.toDouble())
                }
            }
            put("math.max") { args ->
                val av = args.getOrNull(0)
                val bv = args.getOrNull(1)
                if (av is Long && bv is Long) {
                    maxOf(av, bv)
                } else {
                    maxOf(av.toDouble(), bv.toDouble())
                }
            }
            put("math.abs") { args ->
                when (val v = args.getOrNull(0)) {
                    is Long -> kotlin.math.abs(v)
                    is Int -> kotlin.math.abs(v.toLong())
                    else -> kotlin.math.abs(v.toDouble())
                }
            }
            put("math.floor") { args -> kotlin.math.floor(args.getOrNull(0).toDouble()).toLong() }
            put("math.ceil") { args -> kotlin.math.ceil(args.getOrNull(0).toDouble()).toLong() }
            put("math.round") { args -> kotlin.math.round(args.getOrNull(0).toDouble()) }

            // ── str ───────────────────────────────────────────────────────────
            put("str.length") { args -> (args.getOrNull(0)?.toString() ?: "").length.toLong() }
            put("str.toUpper") { args -> args.getOrNull(0)?.toString()?.uppercase() ?: "" }
            put("str.toLower") { args -> args.getOrNull(0)?.toString()?.lowercase() ?: "" }
            put("str.trim") { args -> args.getOrNull(0)?.toString()?.trim() ?: "" }

            // ── list ──────────────────────────────────────────────────────────
            put("list.size") { args -> (args.getOrNull(0) as? List<*>)?.size?.toLong() ?: 0L }
            put("list.contains") { args ->
                val list = args.getOrNull(0) as? List<*> ?: return@put false
                val elem = args.getOrNull(1)
                list.contains(elem)
            }
            put("list.isEmpty") { args -> (args.getOrNull(0) as? List<*>)?.isEmpty() ?: true }

            // ── map ───────────────────────────────────────────────────────────
            put("map.size") { args -> (args.getOrNull(0) as? Map<*, *>)?.size?.toLong() ?: 0L }
            put("map.containsKey") { args ->
                val map = args.getOrNull(0) as? Map<*, *> ?: return@put false
                map.containsKey(args.getOrNull(1))
            }
            put("map.isEmpty") { args -> (args.getOrNull(0) as? Map<*, *>)?.isEmpty() ?: true }

            // ── convert ───────────────────────────────────────────────────────
            put("convert.toInt") { args ->
                when (val v = args.getOrNull(0)) {
                    is Long -> v
                    is Int -> v.toLong()
                    is Double -> v.toLong()
                    is Float -> v.toLong()
                    is String -> v.toLongOrNull() ?: 0L
                    is Boolean -> if (v) 1L else 0L
                    else -> 0L
                }
            }
            put("convert.toReal") { args ->
                when (val v = args.getOrNull(0)) {
                    is Double -> v
                    is Float -> v.toDouble()
                    is Long -> v.toDouble()
                    is Int -> v.toDouble()
                    is String -> v.toDoubleOrNull() ?: 0.0
                    is Boolean -> if (v) 1.0 else 0.0
                    else -> 0.0
                }
            }
            put("convert.toString") { args -> args.getOrNull(0)?.toString() ?: "null" }
            put("convert.toBool") { args ->
                when (val v = args.getOrNull(0)) {
                    is Boolean -> v
                    is Long -> v != 0L
                    is Int -> v != 0
                    is Double -> v != 0.0
                    is String -> v.lowercase() == "true" || v == "1"
                    null -> false
                    else -> true
                }
            }
        }

    /** Returns the [Impl] for [name], or `null` if no built-in by that name exists. */
    public fun lookup(name: String): Impl? = registry[name]

    /** Returns all registered built-in function names. */
    public fun allNames(): Set<String> = registry.keys

    // ── Numeric coercion helper ───────────────────────────────────────────────

    private fun Any?.toDouble(): Double =
        when (this) {
            is Double -> this
            is Float -> toDouble()
            is Long -> toDouble()
            is Int -> toDouble()
            is String -> toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
}
