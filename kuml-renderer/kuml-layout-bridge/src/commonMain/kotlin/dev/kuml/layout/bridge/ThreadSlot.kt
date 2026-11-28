package dev.kuml.layout.bridge

/** A value slot that is per thread on the JVM and a plain variable on single-threaded targets (used by the IBD probes). */
internal expect class ThreadSlot<T : Any>() {
    fun get(): T?

    fun set(value: T?)
}
