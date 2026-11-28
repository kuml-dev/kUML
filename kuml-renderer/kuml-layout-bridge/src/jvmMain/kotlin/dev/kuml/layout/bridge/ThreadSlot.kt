package dev.kuml.layout.bridge

internal actual class ThreadSlot<T : Any> actual constructor() {
    private val local = ThreadLocal<T?>()

    actual fun get(): T? = local.get()

    actual fun set(value: T?) {
        if (value == null) local.remove() else local.set(value)
    }
}
